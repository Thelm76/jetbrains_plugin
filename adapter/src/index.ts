import { randomUUID } from "node:crypto";
import { brotliDecompressSync, gunzipSync, inflateSync } from "node:zlib";
import { config as loadDotEnv } from "dotenv";
import Fastify from "fastify";
import OpenAI from "openai";
import { Command } from "commander";
import { z } from "zod";

loadDotEnv();

const FileChunkSchema = z.object({
  file_path: z.string(),
  start_line: z.number(),
  end_line: z.number(),
  content: z.string(),
  timestamp: z.number().optional(),
});

const UserActionSchema = z.object({
  action_type: z.string(),
  line_number: z.number(),
  offset: z.number(),
  file_path: z.string(),
  timestamp: z.number().optional(),
});

const EditorDiagnosticSchema = z.object({
  line: z.number(),
  start_offset: z.number(),
  end_offset: z.number(),
  severity: z.string(),
  message: z.string(),
  timestamp: z.number().optional(),
});

const NextEditAutocompleteRequestSchema = z.object({
  repo_name: z.string(),
  branch: z.string().nullable().optional(),
  file_path: z.string(),
  file_contents: z.string(),
  recent_changes: z.string(),
  cursor_position: z.number().int().nonnegative(),
  original_file_contents: z.string(),
  file_chunks: z.array(FileChunkSchema).default([]),
  retrieval_chunks: z.array(FileChunkSchema).default([]),
  recent_user_actions: z.array(UserActionSchema).default([]),
  multiple_suggestions: z.boolean().optional(),
  privacy_mode_enabled: z.boolean().optional(),
  client_ip: z.string().nullable().optional(),
  recent_changes_high_res: z.string().optional().default(""),
  changes_above_cursor: z.boolean().optional(),
  ping: z.boolean().optional(),
  editor_diagnostics: z.array(EditorDiagnosticSchema).default([]),
}).refine(
  (request) => request.cursor_position <= request.file_contents.length,
  "cursor_position must be inside file_contents",
);

const EnvSchema = z.object({
  HOST: z.string().default("127.0.0.1"),
  PORT: z.coerce.number().int().positive().default(8081),
  LOG_LEVEL: z.enum(["trace", "debug", "info", "warn", "error", "fatal"]).default("info"),
  OPENAI_API_KEY: z.string().default(""),
  OPENAI_BASE_URL: z.string().url().default("https://api.openai.com/v1"),
  OPENAI_MODEL: z.string().default("gpt-4o-mini"),
  HTTP_REFERER: z.string().optional(),
  X_TITLE: z.string().optional(),
  MAX_TOKENS: z.coerce.number().int().positive().default(512),
  TEMPERATURE: z.coerce.number().default(0),
  REQUEST_TIMEOUT_MS: z.coerce.number().int().positive().default(30_000),
});

type FileChunk = z.infer<typeof FileChunkSchema>;
type UserAction = z.infer<typeof UserActionSchema>;
type EditorDiagnostic = z.infer<typeof EditorDiagnosticSchema>;
type NextEditAutocompleteRequest = z.infer<typeof NextEditAutocompleteRequestSchema>;

type NextEditAutocompletion = {
  start_index: number;
  end_index: number;
  completion: string;
  confidence: number;
  autocomplete_id: string;
};

type NextEditAutocompleteResponse = {
  start_index: number;
  end_index: number;
  completion: string;
  confidence: number;
  autocomplete_id: string;
  elapsed_time_ms: number;
  completions: NextEditAutocompletion[];
};

const command = new Command()
  .option("--port <port>", "Override server port", (value) => Number.parseInt(value, 10))
  .parse(process.argv);

const env = EnvSchema.parse(process.env);
const port = command.opts<{ port?: number }>().port ?? env.PORT;

const fastify = Fastify({
  logger: {
    level: env.LOG_LEVEL,
  },
});

const openai = new OpenAI({
  apiKey: env.OPENAI_API_KEY,
  baseURL: env.OPENAI_BASE_URL,
  timeout: env.REQUEST_TIMEOUT_MS,
  defaultHeaders: {
    ...(env.HTTP_REFERER ? { "HTTP-Referer": env.HTTP_REFERER } : {}),
    ...(env.X_TITLE ? { "X-Title": env.X_TITLE } : {}),
  },
});

const SHUTDOWN_TIMEOUT_MS = 5_000;
const ANSI_DARK_BLUE = "\u001b[34m";
const ANSI_DARK_GREEN = "\u001b[32m";
const ANSI_RESET = "\u001b[0m";
let isShuttingDown = false;

process.once("SIGINT", () => void shutdown("SIGINT"));
process.once("SIGTERM", () => void shutdown("SIGTERM"));

fastify.addContentTypeParser(
  "application/json",
  { parseAs: "buffer" },
  (_request, body, done) => {
    try {
      const buffer = Buffer.isBuffer(body) ? body : Buffer.from(body);
      done(null, JSON.parse(decodeRequestBody(buffer, _request.headers["content-encoding"])));
    } catch (error) {
      done(error as Error);
    }
  },
);

fastify.get("/", async () => healthPayload());
fastify.get("/health", async () => healthPayload());

fastify.post("/backend/next_edit_autocomplete", async (request, reply) => {
  const startedAt = Date.now();
  const parsed = NextEditAutocompleteRequestSchema.safeParse(request.body);

  if (!parsed.success) {
    return reply.code(400).send({
      status: "error",
      error: z.prettifyError(parsed.error),
    });
  }

  const payload = parsed.data;
  reply
    .type("application/x-ndjson; charset=utf-8")
    .header("Cache-Control", "no-cache");

  if (payload.ping) {
    return toJsonLine(buildResponse(payload, "", 0, startedAt));
  }

  try {
    const completion = normalizeInsertion(payload, sanitizeCompletion(await fetchCompletion(payload)));
    return toJsonLine(buildResponse(payload, completion, completion ? 0.72 : 0, startedAt));
  } catch (error) {
    request.log.warn({ err: error }, "Autocomplete upstream request failed");
    return toJsonLine({
      status: "error",
      error: error instanceof Error ? error.message : "Unknown upstream error",
    });
  }
});

try {
  await fastify.listen({ host: env.HOST, port });
} catch (error) {
  fastify.log.fatal({ err: error }, "Failed to start autocomplete adapter");
  process.exit(1);
}

async function shutdown(signal: NodeJS.Signals): Promise<void> {
  if (isShuttingDown) {
    return;
  }

  isShuttingDown = true;
  fastify.log.info({ signal }, "Shutting down autocomplete adapter");

  const timeout = setTimeout(() => {
    fastify.log.error({ signal }, "Timed out while shutting down autocomplete adapter");
    process.exit(1);
  }, SHUTDOWN_TIMEOUT_MS);
  timeout.unref();

  try {
    await fastify.close();
    clearTimeout(timeout);
    process.exit(0);
  } catch (error) {
    clearTimeout(timeout);
    fastify.log.error({ err: error, signal }, "Failed to shut down autocomplete adapter");
    process.exit(1);
  }
}

function healthPayload() {
  return {
    status: "ok",
    service: "sweep-autocomplete-openai-adapter",
    model: env.OPENAI_MODEL,
  };
}

function decodeRequestBody(body: Buffer, contentEncoding: string | string[] | undefined): string {
  const encoding = Array.isArray(contentEncoding)
    ? contentEncoding[0]?.toLowerCase()
    : contentEncoding?.toLowerCase();

  if (encoding === "br" || encoding === "brotli") {
    return brotliDecompressSync(body).toString("utf8");
  }

  if (encoding === "gzip") {
    return gunzipSync(body).toString("utf8");
  }

  if (encoding === "deflate") {
    return inflateSync(body).toString("utf8");
  }

  return body.toString("utf8");
}

async function fetchCompletion(request: NextEditAutocompleteRequest): Promise<string> {
  if (!env.OPENAI_API_KEY) {
    throw new Error("OPENAI_API_KEY is not configured");
  }

  const upstreamRequest = {
    model: env.OPENAI_MODEL,
    temperature: env.TEMPERATURE,
    max_tokens: env.MAX_TOKENS,
    messages: buildMessages(request),
  } satisfies OpenAI.Chat.Completions.ChatCompletionCreateParamsNonStreaming;

  logColoredJson("Outgoing OpenAI-compatible request body", upstreamRequest, ANSI_DARK_BLUE);
  try {
    const completion = await openai.chat.completions.create(upstreamRequest);
    logColoredJson("Incoming OpenAI-compatible response body", completion, ANSI_DARK_GREEN);

    return completion.choices[0]?.message?.content ?? "";
  } catch (error) {
    logColoredJson("Incoming OpenAI-compatible error response body", toLoggableError(error), ANSI_DARK_GREEN);
    throw error;
  }
}

function logColoredJson(title: string, value: unknown, color: string): void {
  process.stdout.write(`${color}${title}:\n${stringifyForLog(value)}\n${ANSI_RESET}`);
}

function stringifyForLog(value: unknown): string {
  return JSON.stringify(value, null, 2) ?? String(value);
}

function toLoggableError(error: unknown): unknown {
  if (!(error instanceof Error)) {
    return error;
  }

  return {
    name: error.name,
    message: error.message,
    stack: error.stack,
    ...Object.fromEntries(Object.entries(error)),
  };
}

function buildMessages(
  request: NextEditAutocompleteRequest,
): OpenAI.Chat.Completions.ChatCompletionMessageParam[] {
  const cursor = clamp(request.cursor_position, 0, request.file_contents.length);
  const prefix = request.file_contents.slice(0, cursor);
  const suffix = request.file_contents.slice(cursor);

  return [
    {
      role: "system",
      content: [
        "You are a low-latency code autocomplete engine.",
        "Return only JSON with this exact shape: {\"completion\":\"text to insert at the cursor\"}.",
        "The completion must be inserted at <CURSOR>; do not repeat the existing prefix or suffix.",
        "Keep the completion minimal and useful. Do not include markdown fences, explanations, or comments about the task.",
        "If no useful completion exists, return {\"completion\":\"\"}.",
      ].join(" "),
    },
    {
      role: "user",
      content: buildPrompt(request, prefix, suffix),
    },
  ];
}

function buildPrompt(request: NextEditAutocompleteRequest, prefix: string, suffix: string): string {
  const chunks = [
    formatChunks("Relevant open file chunks", request.file_chunks),
    formatChunks("Retrieved context chunks", request.retrieval_chunks),
  ].filter(Boolean);

  return [
    `Repository: ${request.repo_name || "unknown"}`,
    `Branch: ${request.branch || "unknown"}`,
    `File: ${request.file_path}`,
    "",
    "Current file with cursor:",
    "```",
    `${truncateLeft(prefix, 16_000)}<CURSOR>${truncate(suffix, 12_000)}`,
    "```",
    "",
    request.recent_changes ? `Recent changes:\n${truncate(request.recent_changes, 6_000)}` : "",
    request.recent_changes_high_res
      ? `Recent high resolution changes:\n${truncate(request.recent_changes_high_res, 6_000)}`
      : "",
    formatDiagnostics(request.editor_diagnostics),
    formatActions(request.recent_user_actions),
    ...chunks,
    "",
    "Return JSON only.",
  ].filter(Boolean).join("\n");
}

function formatChunks(title: string, chunks: FileChunk[]): string {
  if (!chunks.length) {
    return "";
  }

  const rendered = chunks.slice(0, 8).map((chunk) => [
    `--- ${chunk.file_path}:${chunk.start_line}-${chunk.end_line} ---`,
    truncate(chunk.content, 4_000),
  ].join("\n"));

  return `${title}:\n${rendered.join("\n")}`;
}

function formatDiagnostics(diagnostics: EditorDiagnostic[]): string {
  if (!diagnostics.length) {
    return "";
  }

  return "Editor diagnostics:\n" + diagnostics.slice(0, 12)
    .map((diagnostic) => (
      `line ${diagnostic.line}, ${diagnostic.severity}: ${diagnostic.message}`
    ))
    .join("\n");
}

function formatActions(actions: UserAction[]): string {
  if (!actions.length) {
    return "";
  }

  return "Recent user actions:\n" + actions.slice(-12)
    .map((action) => `${action.action_type} at ${action.file_path}:${action.line_number}:${action.offset}`)
    .join("\n");
}

function sanitizeCompletion(value: string): string {
  const parsed = parseCompletionContent(stripMarkdownFence(value.trim()));
  const completion = stripMarkdownFence(parsed)
    .replace(/\r\n/g, "\n")
    .replace(/<\/?CURSOR>/g, "")
    .replace(/^["']([\s\S]*)["']$/g, "$1");

  return /^(no completion|none|null|undefined)$/i.test(completion.trim()) ? "" : completion;
}

function normalizeInsertion(request: NextEditAutocompleteRequest, completion: string): string {
  const cursor = clamp(request.cursor_position, 0, request.file_contents.length);
  const prefix = request.file_contents.slice(0, cursor);
  const suffix = request.file_contents.slice(cursor);

  return trimSuffixOverlap(trimPrefixOverlap(completion, prefix), suffix);
}

function trimPrefixOverlap(completion: string, prefix: string): string {
  const maxOverlap = Math.min(completion.length, prefix.length, 200);
  for (let length = maxOverlap; length > 0; length -= 1) {
    if (prefix.endsWith(completion.slice(0, length))) {
      return completion.slice(length);
    }
  }

  return completion;
}

function trimSuffixOverlap(completion: string, suffix: string): string {
  const maxOverlap = Math.min(completion.length, suffix.length, 200);
  for (let length = maxOverlap; length > 0; length -= 1) {
    if (suffix.startsWith(completion.slice(completion.length - length))) {
      return completion.slice(0, completion.length - length);
    }
  }

  return completion;
}

function parseCompletionContent(content: string): string {
  try {
    const parsed = z.object({
      completion: z.string().optional(),
      completions: z.array(z.object({ completion: z.string() })).optional(),
    }).parse(JSON.parse(content));

    return parsed.completion ?? parsed.completions?.[0]?.completion ?? "";
  } catch {
    const jsonObject = content.slice(content.indexOf("{"), content.lastIndexOf("}") + 1);
    if (jsonObject.length > 1) {
      try {
        const parsed = z.object({ completion: z.string() }).parse(JSON.parse(jsonObject));
        return parsed.completion;
      } catch {
        return content;
      }
    }

    return content;
  }
}

function stripMarkdownFence(value: string): string {
  const fenceMatch = value.match(/^```(?:json|javascript|typescript|ts|[a-zA-Z0-9_-]+)?\s*\r?\n([\s\S]*?)\r?\n```$/);
  return fenceMatch ? fenceMatch[1] ?? "" : value;
}

function buildResponse(
  request: NextEditAutocompleteRequest,
  completion: string,
  confidence: number,
  startedAt: number,
): NextEditAutocompleteResponse {
  const cursor = clamp(request.cursor_position, 0, request.file_contents.length);
  const autocompleteId = randomUUID();
  const item: NextEditAutocompletion = {
    start_index: cursor,
    end_index: cursor,
    completion,
    confidence,
    autocomplete_id: autocompleteId,
  };

  return {
    start_index: item.start_index,
    end_index: item.end_index,
    completion: item.completion,
    confidence: item.confidence,
    autocomplete_id: autocompleteId,
    elapsed_time_ms: Date.now() - startedAt,
    completions: completion ? [item] : [],
  };
}

function toJsonLine(value: unknown): string {
  return `${JSON.stringify(value)}\n`;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max);
}

function truncate(value: string, maxLength: number): string {
  if (value.length <= maxLength) {
    return value;
  }

  return `${value.slice(0, maxLength)}\n...[truncated]`;
}

function truncateLeft(value: string, maxLength: number): string {
  if (value.length <= maxLength) {
    return value;
  }

  return `[truncated]...\n${value.slice(value.length - maxLength)}`;
}
