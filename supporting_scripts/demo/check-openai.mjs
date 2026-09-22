#!/usr/bin/env node
// Small real-provider smoke test. It does not request any course mutations.
import { readFile, writeFile } from 'node:fs/promises';
import { request } from '@playwright/test';
import assert from 'node:assert/strict';
const state = new URL('../../.demo/', import.meta.url);
let key = process.env.OPENAI_API_KEY;
if (!key) {
    const env = await readFile(new URL('openai.env', state), 'utf8');
    key = env
        .split('\n')
        .find((line) => line.startsWith('OPENAI_API_KEY='))
        ?.slice('OPENAI_API_KEY='.length)
        .trim()
        .replace(/^["']|["']$/g, '');
}
assert.ok(key, 'Set OPENAI_API_KEY or .demo/openai.env');
const model = process.env.DEMO_ATLAS_MODEL || 'gpt-5.6-luna';
const response = await fetch('https://api.openai.com/v1/responses', {
    method: 'POST',
    headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, input: 'Reply with exactly OPENAI_READY.', reasoning: { effort: 'low' }, max_output_tokens: 256, store: false }),
    signal: AbortSignal.timeout(120000),
});
assert.ok(response.ok, `OpenAI Responses HTTP ${response.status}`);
const result = await response.json();
assert.equal(result.status, 'completed');
assert.ok(
    result.output.some((item) => item.content?.some((part) => part.text?.includes('OPENAI_READY'))),
    'Provider did not return the expected output',
);
console.log(`OpenAI Responses verified with ${model}.`);
const manifest = JSON.parse(await readFile(new URL('seed-manifest.json', state), 'utf8'));
const api = await request.newContext({ baseURL: manifest.baseURL, timeout: 180000 });
try {
    const login = await api.post('/api/core/public/authenticate', {
        data: { username: process.env.DEMO_USER || 'artemis_admin', password: process.env.DEMO_PASSWORD || 'artemis_admin' },
    });
    assert.ok(login.ok(), `Login HTTP ${login.status()}`);
    const chat = await api.post(`/api/atlas/agent/courses/${manifest.courses.A.id}/chat`, {
        data: { message: 'For this course setup connection check, reply with exactly ATLAS_OPENAI_READY. Do not call any tools, propose a plan, or change any course data.' },
    });
    assert.ok(chat.ok(), `Atlas chat HTTP ${chat.status()}: ${(await chat.text()).slice(0, 500)}`);
    const body = await chat.json();
    assert.equal(body.competenciesModified, false);
    assert.ok(body.message?.includes('ATLAS_OPENAI_READY'), `Unexpected Atlas reply: ${body.message}`);
    await writeFile(
        new URL('openai-verification.json', state),
        JSON.stringify(
            { verifiedAt: new Date().toISOString(), provider: 'OpenAI', responsesModel: model, responsesRequestId: result.id, appChatVerified: true, competenciesModified: false },
            null,
            2,
        ) + '\n',
    );
    console.log('Real OpenAI call through the running Artemis Atlas chat verified; no competencies modified.');
} finally {
    await api.dispose();
}
