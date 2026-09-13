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
const TERMUX_TTS_SPEAK_COMMAND = "termux-tts-speak";
const TERMUX_DIALOG_COMMAND = "termux-dialog";
const TERMUX_DIALOG_TITLE = "Confirm prompt";
// termux-dialog's text widget reports Android's raw DialogInterface button
// codes: BUTTON_POSITIVE (OK) is -1, BUTTON_NEGATIVE (Cancel/dismiss) is -2.
const TERMUX_DIALOG_CONFIRMED_CODE = -1;
const VOICE_CONFIRMATION_ACCEPTED_VALUES = ["y", "yes"];
const VOICE_FLAG_NAME = "--voice";
const GEMINI_API_KEY_ENV_VAR_NAME = "GEMINI_API_KEY";
const GEMINI_MODEL_NAME = "gemini-3.1-flash-lite";
const GEMINI_AUTH_ERROR_HTTP_STATUS = 401;
const GEMINI_RATE_LIMIT_HTTP_STATUS = 429;
const WIDGET_SESSION_CLOSE_DELAY_MS = 3000;

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
 * Speaks the given text aloud via Termux:API's termux-tts-speak. Failures
 * are logged as warnings and never interrupt the CLI's normal output,
 * matching the existing notification-failure handling in displayResultToUser.
 *
 * @param {string} textToSpeak - The text to speak aloud.
 * @param {typeof execFile} [execFileFn] - The execFile implementation to use; defaults to Node's child_process.execFile, overridable in tests.
 * @returns {Promise<void>} Resolves once the speech attempt finishes (success or failure).
 */
function speakResponseAloud(textToSpeak, execFileFn = execFile) {
  return new Promise((resolve) => {
    execFileFn(TERMUX_TTS_SPEAK_COMMAND, [textToSpeak], (execError) => {
      if (execError) {
        console.warn(
          `Warning: could not speak response aloud (${TERMUX_TTS_SPEAK_COMMAND} unavailable or failed): ${execError.message}`,
        );
      }
      resolve();
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
 * Shows the transcript as hint text in a native termux-dialog text input and
 * asks the user to confirm before sending it to Gemini. termux-dialog's
 * text widget only supports hint (placeholder) text via Android's
 * EditText.setHint, not real pre-filled editable content, so the box starts
 * empty: tapping OK with nothing typed confirms the original transcript
 * unchanged, while typing a full replacement and tapping OK sends that
 * instead. Tapping Cancel discards the transcript.
 *
 * @param {string} transcriptText - The transcript to confirm.
 * @param {typeof execFile} [execFileFn] - The execFile implementation to use; defaults to Node's child_process.execFile, overridable in tests.
 * @returns {Promise<{ confirmed: boolean, promptText: string }>} Whether the user confirmed, and the text to send (the typed replacement if any, otherwise the original transcript).
 * @throws {Error} If termux-dialog is unavailable or returns unparseable output; callers should fall back to confirmPromptWithUser on failure.
 */
function confirmPromptWithUserViaDialog(transcriptText, execFileFn = execFile) {
  return new Promise((resolve, reject) => {
    execFileFn(
      TERMUX_DIALOG_COMMAND,
      ["text", "-t", TERMUX_DIALOG_TITLE, "-i", transcriptText],
      (execError, stdout) => {
        if (execError) {
          reject(new Error(`${TERMUX_DIALOG_COMMAND} unavailable or failed: ${execError.message}`));
          return;
        }

        let parsedResult;
        try {
          parsedResult = JSON.parse(stdout);
        } catch (parseError) {
          reject(new Error(`${TERMUX_DIALOG_COMMAND} returned unparseable output: ${parseError.message}`));
          return;
        }

        if (parsedResult.code !== TERMUX_DIALOG_CONFIRMED_CODE) {
          resolve({ confirmed: false, promptText: "" });
          return;
        }

        const typedText = (parsedResult.text || "").trim();
        resolve({ confirmed: true, promptText: typedText || transcriptText });
      },
    );
  });
}

/**
 * Confirms a voice transcript with the user, preferring the native
 * termux-dialog confirmation and falling back to the stdin y/N prompt if
 * the dialog is unavailable or fails.
 *
 * @param {string} transcriptText - The transcript to confirm.
 * @param {object} [dependencies] - Injectable dependencies, overridable in tests.
 * @param {typeof confirmPromptWithUserViaDialog} [dependencies.confirmPromptWithUserViaDialogFn]
 * @param {typeof confirmPromptWithUser} [dependencies.confirmPromptWithUserFn]
 * @returns {Promise<{ confirmed: boolean, promptText: string }>} Whether the user confirmed, and the (possibly edited) text to send.
 */
async function confirmPromptWithFallback(
  transcriptText,
  {
    confirmPromptWithUserViaDialogFn = confirmPromptWithUserViaDialog,
    confirmPromptWithUserFn = confirmPromptWithUser,
  } = {},
) {
  if (!transcriptText) {
    console.log("Heard nothing, cancelling.");
    return { confirmed: false, promptText: "" };
  }

  try {
    return await confirmPromptWithUserViaDialogFn(transcriptText);
  } catch (dialogError) {
    console.warn(`Warning: falling back to text confirmation (${dialogError.message})`);
    const confirmed = await confirmPromptWithUserFn(transcriptText);
    return { confirmed, promptText: transcriptText };
  }
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
 * user (via dialog with stdin fallback), send it to Gemini if confirmed,
 * display the reply, and speak it aloud.
 *
 * @param {GoogleGenAI} geminiClient - The Gemini SDK client to send the request through.
 * @param {object} [dependencies] - Injectable dependencies, overridable in tests.
 * @param {typeof captureVoicePrompt} [dependencies.captureVoicePromptFn]
 * @param {typeof confirmPromptWithFallback} [dependencies.confirmPromptWithFallbackFn]
 * @param {typeof displayResultToUser} [dependencies.displayResultToUserFn]
 * @param {typeof speakResponseAloud} [dependencies.speakResponseAloudFn]
 * @returns {Promise<void>}
 */
async function runVoiceFlow(
  geminiClient,
  {
    captureVoicePromptFn = captureVoicePrompt,
    confirmPromptWithFallbackFn = confirmPromptWithFallback,
    displayResultToUserFn = displayResultToUser,
    speakResponseAloudFn = speakResponseAloud,
  } = {},
) {
  const transcriptText = await captureVoicePromptFn();
  const { confirmed, promptText } = await confirmPromptWithFallbackFn(transcriptText);

  if (!confirmed) {
    console.log("Cancelled.");
    return;
  }

  const geminiResponseText = await sendPromptToGemini(promptText, geminiClient);
  await displayResultToUserFn(geminiResponseText);
  await speakResponseAloudFn(geminiResponseText);
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
    await new Promise((resolve) => setTimeout(resolve, WIDGET_SESSION_CLOSE_DELAY_MS));
    return;
  }

  if (!process.env[GEMINI_API_KEY_ENV_VAR_NAME]) {
    console.error(
      `Error: ${GEMINI_API_KEY_ENV_VAR_NAME} is not set. Add it to a .env file in this directory (see .env.example).`,
    );
    process.exitCode = 1;
    await new Promise((resolve) => setTimeout(resolve, WIDGET_SESSION_CLOSE_DELAY_MS));
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
    await new Promise((resolve) => setTimeout(resolve, WIDGET_SESSION_CLOSE_DELAY_MS));
    return;
  }

  let geminiResponseText;
  try {
    geminiResponseText = await sendPromptToGemini(userPromptText, geminiClient);
  } catch (apiError) {
    console.error(`Error: ${apiError.message}`);
    process.exitCode = 1;
    await new Promise((resolve) => setTimeout(resolve, WIDGET_SESSION_CLOSE_DELAY_MS));
    return;
  }

  await displayResultToUser(geminiResponseText);
  await new Promise((resolve) => setTimeout(resolve, WIDGET_SESSION_CLOSE_DELAY_MS));
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
  confirmPromptWithUserViaDialog,
  confirmPromptWithFallback,
  TERMUX_DIALOG_COMMAND,
  TERMUX_DIALOG_TITLE,
  TERMUX_DIALOG_CONFIRMED_CODE,
  VOICE_CONFIRMATION_ACCEPTED_VALUES,
  runVoiceFlow,
  VOICE_FLAG_NAME,
  displayResultToUser,
  GEMINI_MODEL_NAME,
  speakResponseAloud,
  TERMUX_TTS_SPEAK_COMMAND,
};
