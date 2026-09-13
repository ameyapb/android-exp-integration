const test = require("node:test");
const assert = require("node:assert/strict");
const {
  sendPromptToGemini,
  describeGeminiApiError,
  captureVoicePrompt,
  TERMUX_SPEECH_TO_TEXT_COMMAND,
  confirmPromptWithUser,
  confirmPromptWithUserViaDialog,
  confirmPromptWithFallback,
  TERMUX_DIALOG_COMMAND,
  TERMUX_DIALOG_TITLE,
  runVoiceFlow,
  GEMINI_MODEL_NAME,
  speakResponseAloud,
  TERMUX_TTS_SPEAK_COMMAND,
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

test("confirmPromptWithUserViaDialog resolves confirmed with unedited text on OK", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(null, JSON.stringify({ text: "what's 2+2", code: 0 }));
  };

  const result = await confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile);

  assert.deepEqual(result, { confirmed: true, promptText: "what's 2+2" });
});

test("confirmPromptWithUserViaDialog resolves confirmed with edited text on OK", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(null, JSON.stringify({ text: "what's 3+3", code: 0 }));
  };

  const result = await confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile);

  assert.deepEqual(result, { confirmed: true, promptText: "what's 3+3" });
});

test("confirmPromptWithUserViaDialog resolves not confirmed on cancel", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(null, JSON.stringify({ code: -1 }));
  };

  const result = await confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile);

  assert.deepEqual(result, { confirmed: false, promptText: "" });
});

test("confirmPromptWithUserViaDialog resolves not confirmed on empty edited text", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(null, JSON.stringify({ text: "", code: 0 }));
  };

  const result = await confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile);

  assert.deepEqual(result, { confirmed: false, promptText: "" });
});

test("confirmPromptWithUserViaDialog rejects when termux-dialog is unavailable", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(new Error("command not found"));
  };

  await assert.rejects(
    () => confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile),
    /termux-dialog unavailable or failed/,
  );
});

test("confirmPromptWithUserViaDialog rejects when termux-dialog returns unparseable output", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(null, "not json");
  };

  await assert.rejects(
    () => confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile),
    /termux-dialog returned unparseable output/,
  );
});

test("confirmPromptWithUserViaDialog calls termux-dialog with the transcript pre-filled", async () => {
  const recordedCalls = [];
  const fakeExecFile = (command, args, callback) => {
    recordedCalls.push({ command, args });
    callback(null, JSON.stringify({ text: "what's 2+2", code: 0 }));
  };

  await confirmPromptWithUserViaDialog("what's 2+2", fakeExecFile);

  assert.equal(recordedCalls.length, 1);
  assert.equal(recordedCalls[0].command, TERMUX_DIALOG_COMMAND);
  assert.deepEqual(recordedCalls[0].args, ["text", "-t", TERMUX_DIALOG_TITLE, "-i", "what's 2+2"]);
});

test("confirmPromptWithFallback resolves not confirmed without calling the dialog when transcript is empty", async () => {
  let dialogCalled = false;
  const result = await confirmPromptWithFallback("", {
    confirmPromptWithUserViaDialogFn: async () => {
      dialogCalled = true;
      return { confirmed: true, promptText: "should not happen" };
    },
  });

  assert.equal(dialogCalled, false);
  assert.deepEqual(result, { confirmed: false, promptText: "" });
});

test("confirmPromptWithFallback returns the dialog result when the dialog succeeds", async () => {
  const result = await confirmPromptWithFallback("what's 2+2", {
    confirmPromptWithUserViaDialogFn: async () => ({ confirmed: true, promptText: "what's 3+3" }),
  });

  assert.deepEqual(result, { confirmed: true, promptText: "what's 3+3" });
});

test("confirmPromptWithFallback falls back to the stdin prompt when the dialog throws", async () => {
  const result = await confirmPromptWithFallback("what's 2+2", {
    confirmPromptWithUserViaDialogFn: async () => {
      throw new Error("termux-dialog unavailable or failed: command not found");
    },
    confirmPromptWithUserFn: async (transcriptText) => {
      assert.equal(transcriptText, "what's 2+2");
      return true;
    },
  });

  assert.deepEqual(result, { confirmed: true, promptText: "what's 2+2" });
});

test("confirmPromptWithFallback falls back to a declined stdin prompt when the dialog throws", async () => {
  const result = await confirmPromptWithFallback("what's 2+2", {
    confirmPromptWithUserViaDialogFn: async () => {
      throw new Error("termux-dialog unavailable or failed: command not found");
    },
    confirmPromptWithUserFn: async () => false,
  });

  assert.deepEqual(result, { confirmed: false, promptText: "what's 2+2" });
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
    confirmPromptWithFallbackFn: async () => ({ confirmed: true, promptText: "what's the weather" }),
    displayResultToUserFn: async (text) => {
      recordedDisplayCalls.push(text);
    },
  });

  assert.equal(recordedGeminiCalls.length, 1);
  assert.equal(recordedGeminiCalls[0].contents, "what's the weather");
  assert.deepEqual(recordedDisplayCalls, ["It's sunny."]);
});

test("runVoiceFlow sends the edited prompt text to Gemini, not the raw transcript", async () => {
  const recordedGeminiCalls = [];
  const fakeGeminiClient = {
    models: {
      generateContent: async (request) => {
        recordedGeminiCalls.push(request);
        return { text: "6" };
      },
    },
  };

  await runVoiceFlow(fakeGeminiClient, {
    captureVoicePromptFn: async () => "what's 2+2",
    confirmPromptWithFallbackFn: async () => ({ confirmed: true, promptText: "what's 3+3" }),
    displayResultToUserFn: async () => {},
  });

  assert.equal(recordedGeminiCalls.length, 1);
  assert.equal(recordedGeminiCalls[0].contents, "what's 3+3");
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
    confirmPromptWithFallbackFn: async () => ({ confirmed: false, promptText: "" }),
    displayResultToUserFn: async () => {
      throw new Error("displayResultToUserFn should not be called");
    },
  });

  assert.equal(recordedGeminiCalls.length, 0);
});

test("speakResponseAloud calls termux-tts-speak with the response text", async () => {
  const recordedCalls = [];
  const fakeExecFile = (command, args, callback) => {
    recordedCalls.push({ command, args });
    callback(null, "", "");
  };

  await speakResponseAloud("It's sunny.", fakeExecFile);

  assert.equal(recordedCalls.length, 1);
  assert.equal(recordedCalls[0].command, TERMUX_TTS_SPEAK_COMMAND);
  assert.deepEqual(recordedCalls[0].args, ["It's sunny."]);
});

test("speakResponseAloud warns and resolves when termux-tts-speak fails", async () => {
  const fakeExecFile = (command, args, callback) => {
    callback(new Error("command not found"), "", "");
  };
  const originalWarn = console.warn;
  const recordedWarnings = [];
  console.warn = (message) => recordedWarnings.push(message);

  try {
    await speakResponseAloud("It's sunny.", fakeExecFile);
  } finally {
    console.warn = originalWarn;
  }

  assert.equal(recordedWarnings.length, 1);
  assert.match(recordedWarnings[0], /could not speak response aloud/);
  assert.match(recordedWarnings[0], /command not found/);
});

test("runVoiceFlow speaks the reply aloud after a confirmed prompt", async () => {
  const recordedSpokenText = [];
  const fakeGeminiClient = {
    models: {
      generateContent: async () => ({ text: "It's sunny." }),
    },
  };

  await runVoiceFlow(fakeGeminiClient, {
    captureVoicePromptFn: async () => "what's the weather",
    confirmPromptWithFallbackFn: async () => ({ confirmed: true, promptText: "what's the weather" }),
    displayResultToUserFn: async () => {},
    speakResponseAloudFn: async (text) => {
      recordedSpokenText.push(text);
    },
  });

  assert.deepEqual(recordedSpokenText, ["It's sunny."]);
});

test("runVoiceFlow does not speak when the user does not confirm", async () => {
  const fakeGeminiClient = {
    models: {
      generateContent: async () => ({ text: "should not be reached" }),
    },
  };

  await runVoiceFlow(fakeGeminiClient, {
    captureVoicePromptFn: async () => "what's the weather",
    confirmPromptWithFallbackFn: async () => ({ confirmed: false, promptText: "" }),
    displayResultToUserFn: async () => {
      throw new Error("displayResultToUserFn should not be called");
    },
    speakResponseAloudFn: async () => {
      throw new Error("speakResponseAloudFn should not be called");
    },
  });
});
