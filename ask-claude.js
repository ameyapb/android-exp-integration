#!/usr/bin/env node

/**
 * ask-claude.js
 *
 * Minimal CLI to send a single text prompt to Claude from a Termux shell on
 * Android and surface the reply as a native notification. Delegates to the
 * Claude Code CLI (`claude -p`) so usage is billed against the user's
 * existing Claude Code subscription rather than the metered Anthropic API.
 *
 * Usage:
 *   node ask-claude.js "what's 2+2"
 */

const { execFile } = require("child_process");

const TERMUX_NOTIFICATION_COMMAND = "termux-notification";
const TERMUX_NOTIFICATION_TITLE = "Claude";
const CLAUDE_CODE_COMMAND = "claude";
const CLAUDE_CODE_PRINT_MODE_FLAG = "--print";
const CLAUDE_CODE_OUTPUT_FORMAT_FLAG = "--output-format";
const CLAUDE_CODE_OUTPUT_FORMAT_TEXT = "text";
const CLAUDE_CODE_MAX_OUTPUT_BUFFER_BYTES = 10 * 1024 * 1024;

/**
 * Sends a single user prompt to Claude via the Claude Code CLI and returns
 * the text reply. Reuses the caller's existing `claude login` session
 * instead of requiring a separate Anthropic API key.
 *
 * @param {string} userPromptText - The prompt text supplied by the user.
 * @returns {Promise<string>} Claude's text response.
 * @throws {Error} A human-readable error describing a CLI or auth failure.
 */
function sendPromptToClaude(userPromptText) {
  return new Promise((resolve, reject) => {
    execFile(
      CLAUDE_CODE_COMMAND,
      [
        CLAUDE_CODE_PRINT_MODE_FLAG,
        userPromptText,
        CLAUDE_CODE_OUTPUT_FORMAT_FLAG,
        CLAUDE_CODE_OUTPUT_FORMAT_TEXT,
      ],
      { maxBuffer: CLAUDE_CODE_MAX_OUTPUT_BUFFER_BYTES },
      (execError, stdout, stderr) => {
        if (execError) {
          reject(new Error(describeClaudeCliError(execError, stderr)));
          return;
        }
        resolve(stdout.trim());
      },
    );
  });
}

/**
 * Converts a failure from invoking the Claude Code CLI into a clear,
 * human-readable message.
 *
 * @param {NodeJS.ErrnoException} execError - The error from execFile.
 * @param {string} stderrOutput - Anything the CLI wrote to stderr.
 * @returns {string} A descriptive error message safe to print or notify with.
 */
function describeClaudeCliError(execError, stderrOutput) {
  if (execError.code === "ENOENT") {
    return `Claude Code CLI ("${CLAUDE_CODE_COMMAND}") not found. Install it with ` +
      `"npm install -g @anthropic-ai/claude-code" and run "claude login".`;
  }
  if (stderrOutput && stderrOutput.trim()) {
    return `Claude Code CLI error: ${stderrOutput.trim()}`;
  }
  return `Unexpected error while running the Claude Code CLI: ${execError.message}`;
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
 * Entry point: reads the CLI argument, calls Claude via the Claude Code CLI,
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

  let claudeResponseText;
  try {
    claudeResponseText = await sendPromptToClaude(userPromptText);
  } catch (cliError) {
    console.error(`Error: ${cliError.message}`);
    process.exitCode = 1;
    return;
  }

  await displayResultToUser(claudeResponseText);
}

main();
