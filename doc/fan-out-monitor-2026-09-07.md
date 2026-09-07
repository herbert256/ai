# Funny question fan-out: prepared run, 7 September 2026

## Status: launch blocked before execution

The requested fan-out is configured on the emulator's **Fan Out - run** screen. No fan-out request was sent. Automatic approval review rejected the launch because it would make 1,260 paid calls and transmit report-derived content to the full set of third-party providers without sufficiently specific approval for that scope and cost.

The action was not retried or bypassed. A read after rejection confirmed that the original agent records and all 116 cost rows were unchanged, and `files/secondary/` contained no report-result directories. There are no runtime fan-out findings yet.

## Concrete run awaiting approval

| Setting | Prepared value |
| --- | --- |
| Repository revision | `f5d860adf` |
| Emulator / app process | `emulator-5554` / `com.ai`, PID 7148 at preparation |
| Report | Funny question request — Request for a Funny Question |
| Report ID | `4757c2cc-36e7-4d2e-9a9c-531bca7162dd` |
| Predefined prompt | `response` — **Just the response** |
| Template | `@RESPONSE@`, unchanged |
| Initiators | All 36 successful saved answers |
| Responders | All 36 report models listed below |
| Self-pairs | Excluded |
| Generation requests | 36 × 35 = **1,260** |
| Payload | Each source answer replaces `@RESPONSE@`; its responder receives that source-answer text |
| Existing report total | 116 costed calls, $0.1208429681 |
| Parameter/system-prompt overrides | None added during setup |
| Autostart master | Off (`autostart_items_enabled=false`) |
| Fan Meta preference | On, but the master is off; no separate Fan Meta launch is part of this prepared action |

These are 1,260 logical generation requests, not necessarily 1,260 HTTP operations: provider polling and configured retries can add transport requests. Final charges depend on actual token usage and provider pricing; the setup screen does not supply a fixed dollar quote. No additional budget cap has been imposed.

## Selected destinations

Each responder receives the other 35 models' source answers. Hostnames below come from the existing report's recorded endpoint configuration; the app dispatches requests through its provider-specific API path.

| Provider | Responder model | Recorded endpoint hostname | Source answers |
| --- | --- | --- | ---: |
| AI-ML-API | `mistralai/mistral-nemo` | `api.aimlapi.com` | 35 |
| Alibaba | `qwen3.7-plus` | `ws-dft0j6dsrm76r6ud.eu-central-1.maas.aliyuncs.com` | 35 |
| Amazon | `openai.gpt-oss-20b` | `bedrock-mantle.eu-central-1.api.aws` | 35 |
| Anthropic | `claude-haiku-4-5-20251001` | `api.anthropic.com` | 35 |
| AtlasCloud | `bytedance/doubao-seed-2.0-pro-260215` | `api.atlascloud.ai` | 35 |
| Baseten | `deepseek-ai/DeepSeek-V4-Flash-0731` | `inference.baseten.co` | 35 |
| Cerebras | `gpt-oss-120b` | `api.cerebras.ai` | 35 |
| Chutes | `google/gemma-4-31B-turbo-TEE` | `llm.chutes.ai` | 35 |
| Cohere | `command-r7b-12-2024` | `api.cohere.ai` | 35 |
| DeepInfra | `stepfun-ai/Step-3.7-Flash` | `api.deepinfra.com` | 35 |
| DeepSeek | `deepseek-chat` | `api.deepseek.com` | 35 |
| Fireworks | `accounts/fireworks/models/glm-5p3-flash` | `api.fireworks.ai` | 35 |
| Glama | `google/gemini-2.5-flash-lite` | `glama.ai` | 35 |
| GMI-Cloud | `XiaomiMiMo/MiMo-V2.5-Pro` | `api.gmi-serving.com` | 35 |
| Google | `gemini-2.5-flash` | `generativelanguage.googleapis.com` | 35 |
| Groq | `openai/gpt-oss-20b` | `api.groq.com` | 35 |
| HuggingFace | `deepseek-ai/DeepSeek-V3.1` | `router.huggingface.co` | 35 |
| MergeGateway | `ai21/jamba-1-5-large` | `api-gateway.merge.dev` | 35 |
| MiniMax | `MiniMax-M2.1` | `api.minimax.io` | 35 |
| Mistral | `mistral-small-latest` | `api.mistral.ai` | 35 |
| Moonshot | `kimi-k2.6` | `api.moonshot.ai` | 35 |
| NebiusAIStudio | `google/gemma-3-27b-it` | `api.studio.nebius.com` | 35 |
| Novita.ai | `meta-llama/llama-3.1-8b-instruct` | `api.novita.ai` | 35 |
| NVIDIA | `nvidia/nemotron-3-super-120b-a12b` | `integrate.api.nvidia.com` | 35 |
| OpenAI | `gpt-4o-mini` | `api.openai.com` | 35 |
| OpenRouter | `ibm-granite/granite-4.0-h-micro` | `openrouter.ai` | 35 |
| Parasail | `meta-llama/Llama-3.2-3B-Instruct` | `api.parasail.io` | 35 |
| Perplexity | `sonar` | `api.perplexity.ai` | 35 |
| Replicate | `meta/meta-llama-3-8b-instruct` | `api.replicate.com` | 35 |
| Requesty | `openai-responses/gpt-4.1-nano` | `router.requesty.ai` | 35 |
| SambaNova | `gemma-4-31B-it` | `api.sambanova.ai` | 35 |
| SiliconFlow | `ByteDance-Seed/Seed-OSS-36B-Instruct` | `api.siliconflow.com` | 35 |
| Together | `openai/gpt-oss-20b` | `api.together.xyz` | 35 |
| VercelAIGateway | `mistral/ministral-3b` | `ai-gateway.vercel.sh` | 35 |
| xAI | `grok-3-mini` | `api.x.ai` | 35 |
| Z.AI | `glm-4.5-air` | `api.z.ai` | 35 |

## Preflight source finding

**P2 — Autostart Fan Meta has no runtime consumer.** The preference is exposed by Settings, persisted and imported/exported, and documented as automatically launching Fan Meta after a successful fan-out. A repository-wide search found no runtime read of `autostartFanMeta`. `FanOutEngine.startRun` completes/finalizes without invoking Fan Meta; the fan-out screen's `onLaunchFanMeta` callback is wired to the manual action. This is a source-confirmed wiring gap, not a failure observed in this run. The current emulator's master autostart switch is off, so it would not be expected to autostart Fan Meta here even with that wiring repaired.

References: `ai/src/main/java/com/ai/viewmodel/AppViewModelTypes.kt` (`autostartFanMeta`), `ai/src/main/java/com/ai/viewmodel/FanOutEngine.kt` (`startRun`), `ai/src/main/java/com/ai/ui/report/manage/view/Secondary.kt` (`onLaunchFanMeta`), and `ai/src/main/java/com/ai/ui/settings/SettingsScreen.kt` (Autostart Fan Meta).

## Monitoring prepared for an approved run

- Retain the original report baseline and verify its 36 primary answers remain unchanged.
- Compare the expected 1,260 unique responder/source pairs with persisted placeholders and terminal results; reject missing pairs, duplicates and accidental self-pairs.
- Follow queue/run/error progress, host throttling, retries, timeouts and final HTTP outcomes.
- Inspect final content, usage and trace attribution; distinguish HTTP success from a complete answer.
- Reconcile added report-ledger costs with fan-out rows and any billed retries; check live UI count/total refresh and reopen persistence.
- Inspect crash/ANR and storage warnings, and verify the app remains foreground/responding.

The task-owned logcat capture was stopped after the rejected launch. The emulator is left on the configured run screen. No app source or settings were changed during preparation.
