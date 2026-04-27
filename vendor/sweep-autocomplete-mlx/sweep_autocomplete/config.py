import os

NEXT_EDIT_AUTOCOMPLETE_ENDPOINT = os.environ.get(
    "NEXT_EDIT_AUTOCOMPLETE_ENDPOINT", None
)

# MLX model — use the HuggingFace repo directly (mlx_lm.load handles download & caching)
# Override with MODEL_REPO env var to use a different model (e.g. a local path or custom conversion)
MODEL_REPO = os.environ.get("MODEL_REPO", "Chris-Kode/sweep-next-edit-1.5b-mlx")
