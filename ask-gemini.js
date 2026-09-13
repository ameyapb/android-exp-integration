#!/usr/bin/env node

/**
 * ask-gemini.js
 *
 * Minimal CLI to send a single text prompt to Google's Gemini API from a
 * Termux shell on Android and surface the reply as a native notification.
 * Calls the Gemini free tier directly over HTTPS via the official
 * @google/genai SDK, since the Claude Code CLI does not run in Termux on
 * Android and a local model is too heavy for the target hardware.
 *
 * Usage:
 *   node ask-gemini.js "what's 2+2"
 */

require("dotenv").config({ quiet: true });

const { execFile } = require("child_process");
const readline = require("node:readline");
const { GoogleGenAI } = require("@google/genai");

const TERMUX_NOTIFICATION_COMMAND = "termux-notification";
const TERMUX_NOTIFICATION_TITLE = "Gemini";
const TERMUX_SPEECH_TO_TEXT_COMMAND = "termux-speech-to-text";
const VOICE_CONFIRMATION_ACCEPTED_VALUES = ["y", "yes"];
const VOICE_FLAG_NAME = "--voice";
const GEMINI_API_KEY_ENV_VAR_NAME = "GEMINI_API_KEY";
const GEMINI_MODEL_NAME = "gemini-3.1-flash-lite";
const GEMINI_AUTH_ERROR_HTTP_STATUS = 401;
const GEMINI_RATE_LIMIT_HTTP_STATUS = 429;

/**
 * Sends a single user prompt to Gemini and returns the text reply.
 *
 * @param {string} userPromptText - The prompt text supplied by the user.
 * @param {GoogleGenAI} geminiClient - The Gemini SDK client to send the request through.
 * @returns {Promise<string>} Gemini's trimmed text response.
 * @throws {Error} A human-readable error describing an API failure.
 */
async function sendPromptToGemini(userPromptText, geminiClient) {
  let response;
  try {
    response = await geminiClient.models.generateContent({
      model: GEMINI_MODEL_NAME,
      contents: userPromptText,
    });
  } catch (apiError) {
    throw new Error(describeGeminiApiError(apiError));
  }
  return response.text.trim();
}

/**
 * Converts a failure from calling the Gemini API into a clear,
 * human-readable message.
 *
 * @param {Error & { status?: number }} apiError - The error thrown by the Gemini SDK.
 * @returns {string} A descriptive error message safe to print or notify with.
 */
function describeGeminiApiError(apiError) {
  if (apiError.status === GEMINI_AUTH_ERROR_HTTP_STATUS) {
    return `Gemini API error: ${apiError.message}. Check that ${GEMINI_API_KEY_ENV_VAR_NAME} in .env is set to a valid Google AI Studio key.`;
  }
  if (apiError.status === GEMINI_RATE_LIMIT_HTTP_STATUS) {
    return `Gemini API error: ${apiError.message}. You've hit the free tier rate limit, wait a bit and try again.`;
  }
  return `Gemini API error: ${apiError.message}`;
}

/**
 * Records speech via Termux:API and returns the transcribed text.
 *
 * @param {typeof execFile} [execFileFn] - The execFile implementation to use; defaults to Node's child_process.execFile, overridable in tests.
 * @returns {Promise<string>} The trimmed transcript. Empty string if nothing was heard.
 * @throws {Error} A human-readable error if termux-speech-to-text is unavailable or fails.
 */
function captureVoicePrompt(execFileFn = execFile) {
  return new Promise((resolve, reject) => {
    execFileFn(TERMUX_SPEECH_TO_TEXT_COMMAND, [], (execError, stdout) => {
      if (execError) {
        reject(
          new Error(
            `Voice input error: ${execError.message}. Check that Termux:API is installed (pkg install termux-api) and the Termux:API Android app is granted microphone access.`,
          ),
        );
        return;
      }
      resolve(stdout.trim());
    });
  });
}

/**
 * Prints the transcribed voice prompt and asks the user to confirm sending
 * it to Gemini.
 *
 * @param {string} transcriptText - The transcript to confirm.
 * @param {() => Promise<string>} [readLineFn] - Reads one line of raw stdin input; defaults to reading process.stdin, overridable in tests.
 * @returns {Promise<boolean>} True if the user confirmed, false otherwise (including an empty transcript, which skips prompting).
 */
async function confirmPromptWithUser(transcriptText, readLineFn = readOneLineFromStdin) {
  if (!transcriptText) {
    console.log("Heard nothing, cancelling.");
    return false;
  }

  console.log(`Heard: "${transcriptText}"`);
  const rawAnswer = await readLineFn();
  return VOICE_CONFIRMATION_ACCEPTED_VALUES.includes(rawAnswer.trim().toLowerCase());
}

/**
 * Reads a single line of input from process.stdin.
 *
 * @returns {Promise<string>} The raw line entered by the user.
 */
function readOneLineFromStdin() {
  const readlineInterface = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  });

  return new Promise((resolve) => {
    readlineInterface.question("Send this to Gemini? [y/N] ", (answer) => {
      readlineInterface.close();
      resolve(answer);
    });
  });
}

/**
 * Prints Gemini's response to stdout and attempts to show it as an Android
 * notification via termux-notification. Notification failures are logged as
 * warnings and never interrupt the CLI's normal output.
 *
 * @param {string} geminiResponseText - The text to display to the user.
 * @returns {Promise<void>} Resolves once stdout output and the notification attempt finish.
 */
function displayResultToUser(geminiResponseText) {
  console.log(geminiResponseText);

  return new Promise((resolve) => {
    execFile(
      TERMUX_NOTIFICATION_COMMAND,
      ["--title", TERMUX_NOTIFICATION_TITLE, "--content", geminiResponseText],
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
 * Runs the voice input flow: capture a spoken prompt, confirm it with the
 * user, send it to Gemini if confirmed, and display the reply.
 *
 * @param {GoogleGenAI} geminiClient - The Gemini SDK client to send the request through.
 * @param {object} [dependencies] - Injectable dependencies, overridable in tests.
 * @param {typeof captureVoicePrompt} [dependencies.captureVoicePromptFn]
 * @param {typeof confirmPromptWithUser} [dependencies.confirmPromptWithUserFn]
 * @param {typeof displayResultToUser} [dependencies.displayResultToUserFn]
 * @returns {Promise<void>}
 */
async function runVoiceFlow(
  geminiClient,
  {
    captureVoicePromptFn = captureVoicePrompt,
    confirmPromptWithUserFn = confirmPromptWithUser,
    displayResultToUserFn = displayResultToUser,
  } = {},
) {
  const transcriptText = await captureVoicePromptFn();
  const isConfirmed = await confirmPromptWithUserFn(transcriptText);

  if (!isConfirmed) {
    console.log("Cancelled.");
    return;
  }

  const geminiResponseText = await sendPromptToGemini(transcriptText, geminiClient);
  await displayResultToUserFn(geminiResponseText);
}

/**
 * Entry point: reads the CLI argument, calls Gemini, and displays the
 * result. All failures are caught and reported cleanly instead of throwing
 * an unhandled exception.
 *
 * @returns {Promise<void>}
 */
async function main() {
  const isVoiceMode = process.argv.includes(VOICE_FLAG_NAME);
  const userPromptText = process.argv[2];

  if (!isVoiceMode && !userPromptText) {
    console.error('Usage: node ask-gemini.js "your prompt here"');
    console.error(`   or: node ask-gemini.js ${VOICE_FLAG_NAME}`);
    process.exitCode = 1;
    return;
  }

  if (!process.env[GEMINI_API_KEY_ENV_VAR_NAME]) {
    console.error(
      `Error: ${GEMINI_API_KEY_ENV_VAR_NAME} is not set. Add it to a .env file in this directory (see .env.example).`,
    );
    process.exitCode = 1;
    return;
  }

  const geminiClient = new GoogleGenAI({
    apiKey: process.env[GEMINI_API_KEY_ENV_VAR_NAME],
  });

  if (isVoiceMode) {
    try {
      await runVoiceFlow(geminiClient);
    } catch (voiceError) {
      console.error(`Error: ${voiceError.message}`);
      process.exitCode = 1;
    }
    return;
  }

  let geminiResponseText;
  try {
    geminiResponseText = await sendPromptToGemini(userPromptText, geminiClient);
  } catch (apiError) {
    console.error(`Error: ${apiError.message}`);
    process.exitCode = 1;
    return;
  }

  await displayResultToUser(geminiResponseText);
}

if (require.main === module) {
  main();
}

module.exports = {
  sendPromptToGemini,
  describeGeminiApiError,
  captureVoicePrompt,
  TERMUX_SPEECH_TO_TEXT_COMMAND,
  confirmPromptWithUser,
  VOICE_CONFIRMATION_ACCEPTED_VALUES,
  runVoiceFlow,
  VOICE_FLAG_NAME,
  displayResultToUser,
  GEMINI_MODEL_NAME,
};
