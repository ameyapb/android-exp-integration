#!/usr/bin/env node

/**
 * ask-claude.js
 *
 * Minimal CLI to send a single text prompt to the Claude API from a
 * Termux shell on Android and surface the reply as a native notification.
 *
 * Usage:
 *   node ask-claude.js "what's 2+2"
 */

require("dotenv").config({ quiet: true });

const Anthropic = require("@anthropic-ai/sdk");
const { execFile } = require("child_process");

// Default model for everyday questions asked from the phone: cheap and fast.
// Swap to "claude-sonnet-5" when a question needs higher-quality reasoning
// and the extra cost is worth it.
const CLAUDE_MODEL_NAME = "claude-haiku-4-5-20251001";

const CLAUDE_MAX_RESPONSE_TOKENS = 1024;
const TERMUX_NOTIFICATION_COMMAND = "termux-notification";
const TERMUX_NOTIFICATION_TITLE = "Claude";
const ENVIRONMENT_VARIABLE_NAME_FOR_API_KEY = "ANTHROPIC_API_KEY";

/**
 * Reads and validates the configuration needed to call the Claude API.
 *
 * @returns {{apiKey: string}} The validated configuration.
 * @throws {Error} If the Anthropic API key is missing from the environment.
 */
function readAndValidateConfiguration() {
  const anthropicApiKey = process.env[ENVIRONMENT_VARIABLE_NAME_FOR_API_KEY];

  if (!anthropicApiKey) {
    throw new Error(
      `Missing API key. Set ${ENVIRONMENT_VARIABLE_NAME_FOR_API_KEY} in a .env file in the project root ` +
        `(e.g. "${ENVIRONMENT_VARIABLE_NAME_FOR_API_KEY}=sk-ant-...", see .env.example) or export it in your shell before running this script.`,
    );
  }

  return { apiKey: anthropicApiKey };
}

/**
 * Sends a single user prompt to the Claude API and returns the text reply.
 *
 * @param {string} anthropicApiKey - The Anthropic API key to authenticate with.
 * @param {string} userPromptText - The prompt text supplied by the user.
 * @returns {Promise<string>} Claude's text response.
 * @throws {Error} A human-readable error describing an auth, network, or API failure.
 */
async function sendPromptToClaude(anthropicApiKey, userPromptText) {
  const anthropicClient = new Anthropic({ apiKey: anthropicApiKey });

  let apiResponse;
  try {
    apiResponse = await anthropicClient.messages.create({
      model: CLAUDE_MODEL_NAME,
      max_tokens: CLAUDE_MAX_RESPONSE_TOKENS,
      messages: [{ role: "user", content: userPromptText }],
    });
  } catch (error) {
    throw new Error(describeClaudeApiError(error));
  }

  const responseTextBlocks = apiResponse.content
    .filter((contentBlock) => contentBlock.type === "text")
    .map((contentBlock) => contentBlock.text);

  return responseTextBlocks.join("\n").trim();
}

/**
 * Converts an error thrown by the Anthropic SDK into a clear, human-readable message.
 *
 * @param {unknown} error - The error thrown by the SDK during the API call.
 * @returns {string} A descriptive error message safe to print or notify with.
 */
function describeClaudeApiError(error) {
  if (error instanceof Anthropic.AuthenticationError) {
    return "Claude API rejected the API key. Check that ANTHROPIC_API_KEY is set correctly.";
  }
  if (error instanceof Anthropic.RateLimitError) {
    return "Claude API rate limit exceeded. Wait a moment and try again.";
  }
  if (error instanceof Anthropic.APIConnectionError) {
    return `Network error while reaching the Claude API: ${error.message}`;
  }
  if (error instanceof Anthropic.APIError) {
    return `Claude API error (status ${error.status}): ${error.message}`;
  }
  return `Unexpected error while calling the Claude API: ${error.message || error}`;
}

/**
 * Prints Claude's response to stdout and attempts to show it as an Android
 * notification via termux-notification. Notification failures are logged as
 * warnings and never interrupt the CLI's normal output.
 *
 * @param {string} claudeResponseText - The text to display to the user.
 * @returns {Promise<void>} Resolves once stdout output and the notification attempt finish.
 */
function displayResultToUser(claudeResponseText) {
  console.log(claudeResponseText);

  return new Promise((resolve) => {
    execFile(
      TERMUX_NOTIFICATION_COMMAND,
      ["--title", TERMUX_NOTIFICATION_TITLE, "--content", claudeResponseText],
      (execError) => {
        if (execError) {
          console.warn(
            `Warning: could not show Android notification (${TERMUX_NOTIFICATION_COMMAND} unavailable or failed): ${execError.message}`,
          );
        }
        resolve();
      },
    );
  });
}

/**
 * Entry point: reads the CLI argument, validates configuration, calls Claude,
 * and displays the result. All failures are caught and reported cleanly
 * instead of throwing an unhandled exception.
 *
 * @returns {Promise<void>}
 */
async function main() {
  const userPromptText = process.argv[2];

  if (!userPromptText) {
    console.error('Usage: node ask-claude.js "your prompt here"');
    process.exitCode = 1;
    return;
  }

  let configuration;
  try {
    configuration = readAndValidateConfiguration();
  } catch (configurationError) {
    console.error(`Error: ${configurationError.message}`);
    process.exitCode = 1;
    return;
  }

  let claudeResponseText;
  try {
    claudeResponseText = await sendPromptToClaude(configuration.apiKey, userPromptText);
  } catch (apiError) {
    console.error(`Error: ${apiError.message}`);
    process.exitCode = 1;
    return;
  }

  await displayResultToUser(claudeResponseText);
}

main();
