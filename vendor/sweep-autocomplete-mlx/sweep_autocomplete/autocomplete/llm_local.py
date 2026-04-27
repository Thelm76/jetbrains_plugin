import threading
import time
from typing import Any

from loguru import logger

from sweep_autocomplete.config import MODEL_REPO

_model = None
_tokenizer = None
_model_lock = threading.Lock()
_request_lock = threading.Lock()
_latest_request_id = 0


class RequestCancelled(Exception):
    """Raised when a queued request is superseded by a newer one."""
    pass


def get_model():
    global _model, _tokenizer
    if _model is None:
        import mlx_lm

        logger.info(f"Loading MLX model from {MODEL_REPO}")
        _model, _tokenizer = mlx_lm.load(MODEL_REPO)
        logger.info("MLX model loaded successfully")
    return _model, _tokenizer


def generate_completion(
    prompt: str,
    stop: list[str],
    max_tokens: int,
    temperature: float,
    prefix: str = "",
) -> tuple[str, int, list[Any], str | None]:
    """Generate a completion using the local MLX model with stream_generate.

    Uses stream_generate for early stop-sequence detection so we don't
    waste time generating tokens past a stop sequence.

    Only the latest request will actually run inference. If a newer request
    arrives while this one is waiting for the model lock, this request is
    cancelled (raises RequestCancelled).

    Returns (completion_text, elapsed_ms, logprobs, finish_reason)
    """
    global _latest_request_id
    from mlx_lm import stream_generate
    from mlx_lm.sample_utils import make_sampler

    model, tokenizer = get_model()
    full_prompt = prompt + prefix if prefix else prompt

    # Claim a request ID — always monotonically increasing
    with _request_lock:
        _latest_request_id += 1
        my_id = _latest_request_id

    # Wait for the model. When we get the lock, check if we're still latest.
    with _model_lock:
        if my_id != _latest_request_id:
            logger.info(f"Request {my_id} cancelled (latest is {_latest_request_id})")
            raise RequestCancelled()

        tokens = tokenizer.encode(full_prompt)
        logger.info(f"Prompt length: {len(full_prompt)} chars, {len(tokens)} tokens")

        start = time.time()

        sampler = make_sampler(temp=temperature if temperature > 0 else 0.0)

        text_parts = []
        finish_reason = "stop"
        hit_stop = False

        for response in stream_generate(
            model=model,
            tokenizer=tokenizer,
            prompt=full_prompt,
            max_tokens=max_tokens,
            sampler=sampler,
            prefill_step_size=4096,
        ):
            text_parts.append(response.text)

            # Check for stop sequences as tokens stream in
            accumulated = "".join(text_parts)
            for s in stop:
                if s in accumulated:
                    text_parts = [accumulated[:accumulated.index(s)]]
                    hit_stop = True
                    break
            if hit_stop:
                break

        elapsed_ms = int((time.time() - start) * 1000)

    text = "".join(text_parts)

    if not hit_stop:
        token_count = len(tokenizer.encode(text, add_special_tokens=False))
        if token_count >= max_tokens:
            finish_reason = "length"

    if prefix:
        text = prefix + text

    return text, elapsed_ms, [], finish_reason
