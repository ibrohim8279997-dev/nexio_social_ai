# Configuration

Everything is configured inside the app. Nothing is set at build time, and no key belongs in source.

## AI providers

**Settings > AI providers and models**, or the drawer's **API Manager**.

1. Add a provider and paste your API key.
2. Tap **Test connection and find models**. Listing models spends no tokens.
3. Tick the models you want to use. Unticked models are never used.
4. Tap a model to correct what it can do and to enter its prices.

Prices come from your provider's pricing page and stay yours to update. They are copied onto each
expense row, so later changes never rewrite history. A model without a price still works, but its
requests show as "No price" instead of a cost.

| Provider | Text | Vision | Images | Video | Speech |
| --- | --- | --- | --- | --- | --- |
| Anthropic Claude | Yes | Yes | No | No | No |
| OpenAI | Yes | Yes | Yes | Yes | Yes |
| Google Gemini | Yes | Yes | Yes | Yes | No |
| Groq | Yes | Model dependent | No | No | No |
| OpenAI-compatible | Depends on the service | | | | |

Model names and capabilities change. Availability above reflects the APIs at the time of writing;
the app reads identifiers from each provider rather than assuming them. Anthropic's API does not
accept a temperature setting, so that field has no effect on Claude models.

Set spending limits with the provider as well as in the app. A key stored on a phone can never be
made impossible to extract from a compromised device.

## Agents

**AI Agents**, then tap an agent. Choose a model and a fallback, and optionally a temperature, an
output limit and standing instructions. Agents with no model use the first selected model of the
default provider that can do the job. Pick a stronger model for the Main, Marketing and Analytics
agents, and an independent one for Quality Control.

## Budgets

**AI Expenses > Monthly budget** sets the budget, the warning percentage and an optional hard stop.
At the hard stop, new paid requests are refused until the next month or until you raise it.

Estimated and measured amounts count toward limits. Requests without a price cannot, which is
another reason to enter prices.

## Instagram

See [INSTAGRAM.md](INSTAGRAM.md).

## Language

**Settings > App language** switches the interface between English and Russian, or follows the
phone. **Settings > Language for AI content** separately controls what the agents write in.
