const test = require("node:test");
const assert = require("node:assert/strict");
const {
  sendPromptToGemini,
  describeGeminiApiError,
  captureVoicePrompt,
  TERMUX_SPEECH_TO_TEXT_COMMAND,
  confirmPromptWithUser,
  runVoiceFlow,
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

test("confirmPromptWithUser resolves false without prompting when the transcript is empty", async () => {
  let readLineCallCount = 0;
  const fakeReadLine = async () => {
    readLineCallCount += 1;
    return "y";
  };

  const confirmed = await confirmPromptWithUser("", fakeReadLine);

  assert.equal(confirmed, false);
  assert.equal(readLineCallCount, 0);
});

test("confirmPromptWithUser resolves true when the user types y", async () => {
  const fakeReadLine = async () => "y";

  const confirmed = await confirmPromptWithUser("what's 2+2", fakeReadLine);

  assert.equal(confirmed, true);
});

test("confirmPromptWithUser resolves true when the user types Yes in any case", async () => {
  const fakeReadLine = async () => "Yes";

  const confirmed = await confirmPromptWithUser("what's 2+2", fakeReadLine);

  assert.equal(confirmed, true);
});

test("confirmPromptWithUser resolves false when the user types anything else", async () => {
  const fakeReadLine = async () => "n";

  const confirmed = await confirmPromptWithUser("what's 2+2", fakeReadLine);

  assert.equal(confirmed, false);
});

test("confirmPromptWithUser resolves false on empty input", async () => {
  const fakeReadLine = async () => "";

  const confirmed = await confirmPromptWithUser("what's 2+2", fakeReadLine);

  assert.equal(confirmed, false);
});

test("runVoiceFlow sends the confirmed transcript to Gemini and displays the reply", async () => {
  const recordedGeminiCalls = [];
  const recordedDisplayCalls = [];
  const fakeGeminiClient = {
    models: {
      generateContent: async (request) => {
        recordedGeminiCalls.push(request);
        return { text: "It's sunny." };
      },
    },
  };

  await runVoiceFlow(fakeGeminiClient, {
    captureVoicePromptFn: async () => "what's the weather",
    confirmPromptWithUserFn: async () => true,
    displayResultToUserFn: async (text) => {
      recordedDisplayCalls.push(text);
    },
  });

  assert.equal(recordedGeminiCalls.length, 1);
  assert.equal(recordedGeminiCalls[0].contents, "what's the weather");
  assert.deepEqual(recordedDisplayCalls, ["It's sunny."]);
});

test("runVoiceFlow does not call Gemini when the user does not confirm", async () => {
  const recordedGeminiCalls = [];
  const fakeGeminiClient = {
    models: {
      generateContent: async (request) => {
        recordedGeminiCalls.push(request);
        return { text: "should not be reached" };
      },
    },
  };

  await runVoiceFlow(fakeGeminiClient, {
    captureVoicePromptFn: async () => "what's the weather",
    confirmPromptWithUserFn: async () => false,
    displayResultToUserFn: async () => {
      throw new Error("displayResultToUserFn should not be called");
    },
  });

  assert.equal(recordedGeminiCalls.length, 0);
});
