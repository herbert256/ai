# Funny question Fan Meta — prepared launch

**Status: not launched.** Automatic approval review rejected the launch because of report-text disclosure to external providers and API charges. No Fan Meta request has been sent.

## Exact scope

- Report: **Funny question request** (`4757c2cc-36e7-4d2e-9a9c-531bca7162dd`).
- Existing Fan Out: **response — Just the response** (`594de4a7-854c-4004-971f-8e70f879fa87`).
- **1,256 successful Fan Out answers** are eligible for title-and-icon generation. The four existing failed answers are excluded.
- Use the saved **workers** swarm, with the 19 provider/model destinations below. Each item selects a worker; failed attempts can fall back to another member.
- Each request sends one saved Fan Out answer inside the existing prompt below. The same answer can reach more than one pool member when fallback is needed.
- Saved Fan Out content totals **519,578 characters** across eligible items; median **136**, maximum **23,117**, before adding the prompt.
- API charges apply. There is no new agreed monetary cap, and the final cost depends on input/output usage and retries.
- Existing primary answers, Fan Out responses and accrued costs are retained.

```text
Please analyse below text

Reply with EXACTLY two lines and nothing else:
title: <a title for the text, maximum 30 characters>
icon: <single fitting emoji>

TEXT:
@PROMPT@
```

## Configured destinations

| Provider | Model | API origin |
|---|---|---|
| Mistral | `ministral-14b-latest` | `https://api.mistral.ai:443` |
| OpenAI | `gpt-4o-mini` | `https://api.openai.com:443` |
| Groq | `openai/gpt-oss-20b` | `https://api.groq.com:443` |
| Cerebras | `gpt-oss-120b` | `https://api.cerebras.ai:443` |
| DeepSeek | `deepseek-v4-flash` | `https://api.deepseek.com:443` |
| Google | `gemini-2.5-flash-lite` | `https://generativelanguage.googleapis.com:443` |
| Anthropic | `claude-haiku-4-5-20251001` | `https://api.anthropic.com:443` |
| xAI | `grok-4-1-fast-non-reasoning` | `https://api.x.ai:443` |
| Cohere | `command-r7b-12-2024` | `https://api.cohere.ai:443` |
| DeepInfra | `google/gemma-3-12b-it` | `https://api.deepinfra.com:443` |
| Together | `openai/gpt-oss-20b` | `https://api.together.xyz:443` |
| Parasail | `google/gemma-3-4b-it` | `https://api.parasail.io:443` |
| NebiusAIStudio | `Qwen/Qwen3-30B-A3B-Instruct-2507` | `https://api.studio.nebius.com:443` |
| Novita.ai | `meta-llama/llama-3.1-8b-instruct` | `https://api.novita.ai:443` |
| OpenRouter | `ibm-granite/granite-4.0-h-micro` | `https://openrouter.ai:443` |
| VercelAIGateway | `mistral/ministral-3b` | `https://ai-gateway.vercel.sh:443` |
| Amazon | `openai.gpt-oss-20b` | `https://bedrock-mantle.eu-central-1.api.aws:443` |
| Requesty | `openai-responses/gpt-4.1-nano` | `https://router.requesty.ai:443` |
| Glama | `google/gemini-2.5-flash-lite` | `https://glama.ai:443` |

All nineteen configured keys are present. Keys and authorization headers are excluded from this document. The pool passed the earlier [workers smoke check](workers-health-2026-09-07.md); this does not establish the outcome of this much larger metadata batch.

## Baseline and monitoring prepared

The baseline contains **36 successful primary answers**, **1,260 Fan Out rows** (1,256 successful / 4 errors), **1,380 existing cost entries**, and **$0.8235966389** recorded lifetime report cost. No Fan Meta titles, icons or run IDs exist. The report uses prompt-configured workers and has metadata enabled.

Temporary before-state copies and the read-only progress/trace collector are under `/tmp/funny-fanmeta-20260907/`. The temporary log collector and keep-awake monitor were stopped while awaiting approval; the last sample showed zero new calls, zero touched metadata rows, unchanged primary answers and unchanged existing cost records. Planned checks cover completion, worker fallbacks, usable title/icon output, timings, saved trace attribution, accounting, UI/persistence agreement and preservation of original answer fields.

## Pre-launch source observation

The generic batch work review can show the original report agents instead of the actual Fan Meta worker pool. `runFanMetaBatch` invokes `runThrottledBatch` without an explicit `ReportWorkPlan`; `ReportWorkLimits.savedWorkPlan` falls back to `report.agents` when it finds no evidence prompt for the newly generated Fan Meta run ID. Those original 36 agent configurations differ from this 19-worker pool. This is a source-confirmed review-context issue; the live Fan Meta work-review dialog has not yet been opened. The destination table above was resolved directly from the current saved swarm and provider registry.

The rejected action was the Fan Meta entry button. Source inspection shows that button initially opens a local “Start Fan Meta job” confirmation. No retry of the rejected action or alternative launch path was used. Explicit approval of the payload, provider scope and charges is required before proceeding.
