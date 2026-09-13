const test = require("node:test");
const assert = require("node:assert/strict");
const {
  sendPromptToGemini,
  describeGeminiApiError,
  captureVoicePrompt,
  TERMUX_SPEECH_TO_TEXT_COMMAND,
  GEMINI_MODEL_NAME,
} = require("./ask-gemini.js");

test("sendPromptToGemini sends the prompt to the configured model and returns the trimmed text reply", async () => {
  const recordedCalls = [];
  const fakeGeminiClient = {
    models: {
      generateContent: async (request) => {
        recordedCalls.push(request);
        return { text: "  4  " };
      },
    },
  };

  const reply = await sendPromptToGemini("what's 2+2", fakeGeminiClient);

  assert.equal(reply, "4");
  assert.equal(recordedCalls.length, 1);
  assert.deepEqual(recordedCalls[0], {
    model: GEMINI_MODEL_NAME,
    contents: "what's 2+2",
  });
});

test("sendPromptToGemini surfaces a readable error when the Gemini API call fails", async () => {
  const apiFailure = Object.assign(new Error("invalid API key"), {
    status: 401,
  });
  const fakeGeminiClient = {
    models: {
      generateContent: async () => {
        throw apiFailure;
      },
    },
  };

  await assert.rejects(
    () => sendPromptToGemini("hello", fakeGeminiClient),
    (thrownError) => {
      assert.match(thrownError.message, /Gemini API error/);
      assert.match(thrownError.message, /invalid API key/);
      return true;
    },
  );
});

test("describeGeminiApiError reports a clear message for an authentication failure", () => {
  const apiFailure = Object.assign(new Error("API key not valid"), {
    status: 401,
  });

  const message = describeGeminiApiError(apiFailure);

  assert.match(message, /Gemini API error/);
  assert.match(message, /API key not valid/);
});

test("describeGeminiApiError reports a clear message for a rate limit failure", () => {
  const apiFailure = Object.assign(new Error("Resource exhausted"), {
    status: 429,
  });

  const message = describeGeminiApiError(apiFailure);

  assert.match(message, /Gemini API error/);
  assert.match(message, /Resource exhausted/);
});

test("captureVoicePrompt returns the trimmed transcript from termux-speech-to-text", async () => {
  const recordedCalls = [];
  const fakeExecFile = (command, args, callback) => {
    recordedCalls.push({ command, args });
    callback(null, "  what's the weather today  \n", "");
  };

  const transcript = await captureVoicePrompt(fakeExecFile);

  assert.equal(transcript, "what's the weather today");
  assert.equal(recordedCalls.length, 1);
  assert.equal(recordedCalls[0].command, TERMUX_SPEECH_TO_TEXT_COMMAND);
});

test("captureVoicePrompt surfaces a readable error when termux-speech-to-text fails", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(new Error("command not found"), "", "");
  };

  await assert.rejects(
    () => captureVoicePrompt(fakeExecFile),
    (thrownError) => {
      assert.match(thrownError.message, /Voice input error/);
      assert.match(thrownError.message, /command not found/);
      return true;
    },
  );
});
