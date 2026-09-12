# GGUF Model Guide

## Model requirements

Prefer a model that:

- Is an Instruction or Chat model rather than an unaligned Base model.
- Understands Japanese and reliably produces the selected Chinese variant.
- Follows concise formatting instructions without adding explanations.
- Uses a chat template supported by the selected llama.cpp release.
- Comes from a trusted publisher and matches the original model documentation.
- Fits in the available GPU memory with enough space for the context and compute buffers.

## Quantization starting points

`Q4_K_M` is a practical first download. Higher-bit quantizations can improve quality slightly but require more memory and storage.

| Available VRAM | Practical starting point |
| --- | --- |
| 6–8 GB | 7B/8B at Q4 |
| 10–12 GB | 8B–14B at Q4 |
| 16 GB | Around 14B at Q4, or a higher quantization of a smaller model |

These are memory-oriented starting points, not a translation-quality ranking. Context size, batch size, GPU applications, and model architecture all affect actual memory use.

## Runtime defaults

For the first compatibility test, keep the supplied defaults:

- Context Size: `8192`
- GPU Layers: `999` to request maximum practical offload
- Batch Size: `512`
- UBatch Size: `256`
- CPU Threads: `0` for automatic selection
- Flash Attention: `on`
- Prompt Cache: enabled
- Metrics: enabled
- Slots: enabled

If loading fails because of memory pressure, reduce Context Size first. If necessary, use a smaller model or quantization, then reduce Batch Size and UBatch Size. UBatch Size must not exceed Batch Size.

## Model ID and profiles

The Model ID is the alias reported by llama-server. It must exactly match the Model ID entered in the Android app. Use a short, stable identifier containing only ASCII letters, digits, `.`, `_`, `:`, or `-`.

Profiles store runtime settings for different models. Saving another profile makes it active, but a running service must be restarted before runtime changes take effect. Up to 12 profiles are supported.

## Thinking control

The **Force-disable model thinking** option is off by default. Leave it off unless the model documentation explicitly supports disabling reasoning and testing shows that reasoning content is interfering with responses.

Some translation fine-tunes, including Sakura-14B-Qwen3-v1.5, may immediately produce an end token and an empty translation when thinking is forcibly disabled. For such models, follow the model default.

FgoGotran Local now detects the specific HTTP 200, empty-content, end-token response during startup. It retries once using the model default and remembers the result for the same GGUF and llama-server files. If the fallback probe also fails, inspect the compatibility result and runtime log rather than repeatedly restarting. The Android request does not need a `/no_think` suffix when server-side thinking control is used.

## Evaluating a model

Test more than one representative FGO scene. Evaluate:

- Correct Japanese-to-Chinese meaning.
- Stable output structure and preserved line breaks.
- Names and glossary terms.
- Omitted subjects and character relationships.
- Response latency after the first request.
- Failure rate across repeated requests.

The built-in compatibility test alone does not measure translation quality.
