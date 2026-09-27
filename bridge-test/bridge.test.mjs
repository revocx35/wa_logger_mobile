// Drives app/src/main/assets/walm-bridge.js against a fake WhatsApp Web (fakewa.mjs).
// Run: node --test bridge-test/
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { MsgKey, MsgModel, Wid, call, load, makeWindow, tick, until } from './fakewa.mjs';

const CHAT = '905551112233@c.us';

function msg(id, attrs = {}, keyOver = {}) {
  return new MsgModel({
    id: new MsgKey({ fromMe: false, remote: CHAT, id, ...keyOver }),
    type: 'chat',
    body: `text ${id}`,
    t: 1_700_000_000,
    from: new Wid(CHAT),
    to: new Wid('905550000001@c.us'),
    ack: 1,
    isNewMsg: true,
    ...attrs,
  });
}

async function started(opts) {
  const env = makeWindow(opts);
  load(env);
  await until(env, (m) => m.t === 'hello');
  env.ready();
  await until(env, (m) => m.t === 'wa');
  return env;
}

async function linked() {
  const env = await started({ state: 'CONNECTED' });
  env.Socket.hasSynced = true;
  env.Socket.emit('change:hasSynced');
  await until(env, (m) => m.t === 'ready');
  return env;
}

const evs = (env, name) => env.posted.filter((m) => m.t === 'ev' && m.ev === name);

test('says hello at once, then reports WhatsApp Web once its modules exist', async () => {
  const env = makeWindow({ state: 'OPENING' });
  load(env);
  assert.equal(env.posted[0].t, 'hello');
  assert.equal(env.posted[0].v, 1);
  await tick(300);
  assert.equal(env.posted.filter((m) => m.t === 'wa').length, 0);
  env.ready('2.3000.1');
  const wa = await until(env, (m) => m.t === 'wa');
  assert.deepEqual(wa, { t: 'wa', version: '2.3000.1', state: 'OPENING' });
  // Device name shown on the phone.
  const info = env.modules.WAWebMiscBrowserUtils.info();
  assert.equal(info.os, 'wa_logger mobile');
  assert.equal(info.name, 'Chrome');
  // Loading the script twice (reload race) must not double-hook.
  load(env);
  assert.equal(env.posted.filter((m) => m.t === 'hello').length, 1);
});

test('builds the QR payload like whatsapp-web.js and refreshes it', async () => {
  const env = await started({ state: 'UNPAIRED' });
  const qr = await until(env, (m) => m.t === 'qr');
  const b64 = (s) => Buffer.from(s).toString('base64');
  assert.equal(qr.qr, 'https://wa.me/settings/linked_devices#' + ['REF1', b64('NOISE'), b64('IDENT'), 'ADV', 'Chrome'].join(','));
  env.Conn.ref = 'REF2';
  env.Conn.emit('change:ref');
  await until(env, (m) => m.t === 'qr' && m.qr.includes('#REF2,'));
  env.Socket.state = 'UNPAIRED_IDLE';
  env.Socket.emit('change:state', env.Socket, 'UNPAIRED_IDLE');
  await until(env, (m) => m.t === 'state' && m.state === 'UNPAIRED_IDLE');
  await call(env, 'refreshQr');
  await until(env, (m) => m.t === 'qr' && m.qr.includes('#REF-refreshed,'));
});

test('pairing code for linking on the same phone', async () => {
  const env = await started({ state: 'UNPAIRED' });
  const code = await call(env, 'pairingCode', { phone: '+90 555 111 22 33' });
  assert.equal(code, 'ABCD1234');
  assert.equal(env.modules.WAWebAltDeviceLinkingApi.phone, '905551112233');
  assert.equal(env.modules.WAWebAltDeviceLinkingApi.type, 'ALT_DEVICE_LINKING');
  await assert.rejects(call(env, 'pairingCode', { phone: '12' }), /bad phone number/);
  await call(env, 'cancelPairing');
  assert.equal(env.modules.WAWebAltDeviceLinkingApi.qr, true);
});

test('hasSynced → authenticated → ready with the own account', async () => {
  const env = await linked();
  const auth = env.posted.findIndex((m) => m.t === 'authenticated');
  const ready = env.posted.findIndex((m) => m.t === 'ready');
  assert.ok(auth >= 0 && ready > auth);
  assert.deepEqual(env.posted[ready].me, { id: '905550000001@c.us', lid: '111@lid', name: 'Owner' });
  // The hidden session must not show the owner as online.
  assert.equal(env.modules.WAWebPresenceChatAction.sent, 1);
  // Placeholder resend (undecryptable messages) is enabled, as in whatsapp-web.js.
  assert.equal(env.modules.WAWebSyncGatingUtils.isPlaceholderMessageResendEnabled(), true);
});

test('ready also happens when WhatsApp was already synced before the bridge looked', async () => {
  const env = await started({ state: 'CONNECTED', synced: true });
  await until(env, (m) => m.t === 'ready');
});

test('new messages are serialized with a full key, JIDs as strings and no functions', async () => {
  const env = await linked();
  const m = msg('A1', {
    author: new Wid('x@c.us'),
    mentionedJidList: [new Wid('y@c.us'), 'z@c.us'],
    quotedMsg: { type: 'image', body: 'THUMB', caption: 'orig' },
    quotedStanzaID: 'Q1',
    quotedParticipant: new Wid('x@c.us'),
    latestEditMsgKey: new MsgKey({ fromMe: false, remote: CHAT, id: 'EDIT' }),
    vcardList: [{ displayName: 'A', vcard: 'BEGIN:VCARD' }],
    pollOptions: [{ name: 'Yes', localId: 0 }],
    mediaData: { huge: true },
    someFunction: () => 1,
  });
  env.Msg.add(m);
  const ev = (await until(env, (x) => x.t === 'ev' && x.ev === 'message')).msg;
  assert.equal(ev.id._serialized, `false_${CHAT}_A1`);
  assert.equal(ev.id.remote, CHAT);
  assert.equal(ev.from, CHAT);
  assert.equal(ev.author, 'x@c.us');
  assert.deepEqual(ev.mentionedJidList, ['y@c.us', 'z@c.us']);
  assert.deepEqual(ev.quotedMsg, { type: 'image', body: 'THUMB', caption: 'orig' });
  assert.equal(ev.quotedParticipant, 'x@c.us');
  assert.equal(ev.latestEditMsgKey._serialized, `false_${CHAT}_EDIT`);
  assert.deepEqual(ev.vcardList, [{ displayName: 'A', vcard: 'BEGIN:VCARD' }]);
  assert.deepEqual(ev.pollOptions, [{ name: 'Yes' }]);
  assert.equal(ev.mediaData, undefined);
  assert.equal(ev.body, 'text A1');
  // Old messages loaded into the collection (not new) are not events.
  env.Msg.add(msg('OLD', { isNewMsg: false }));
  await tick(20);
  assert.equal(evs(env, 'message').length, 1);
});

test('group participant keys keep the participant (MsgKey.toString)', async () => {
  const env = await linked();
  env.Msg.add(new MsgModel({ id: new MsgKey({ fromMe: false, remote: '1@g.us', id: 'G1', participant: '5@lid' }), type: 'gp2', subtype: 'add', t: 1, isNewMsg: true, recipients: [new Wid('6@lid')] }));
  const ev = (await until(env, (x) => x.t === 'ev' && x.ev === 'message')).msg;
  assert.equal(ev.id._serialized, 'false_1@g.us_G1_5@lid');
  assert.equal(ev.id.participant, '5@lid');
  assert.deepEqual(ev.recipients, ['6@lid']);
  assert.equal(ev.type, 'gp2');
});

test('revokes carry the original when it was seen, acks/remove/edits/chats are forwarded', async () => {
  const env = await linked();
  const m = msg('R1', { body: 'secret' });
  env.Msg.add(m);
  await until(env, (x) => x.t === 'ev' && x.ev === 'message');
  m.type = 'revoked';
  m.body = undefined;
  env.Msg.emit('change:type', m);
  const rv = await until(env, (x) => x.t === 'ev' && x.ev === 'revoke');
  assert.equal(rv.after.type, 'revoked');
  assert.equal(rv.before.body, 'secret');
  assert.equal(rv.before.id._serialized, `false_${CHAT}_R1`);

  const unseen = msg('R2', { type: 'revoked' });
  env.Msg.emit('change:type', unseen);
  const rv2 = await until(env, (x) => x.t === 'ev' && x.ev === 'revoke' && x.after.id.id === 'R2');
  assert.equal(rv2.before, null);

  env.Msg.emit('change:ack', m, 3);
  assert.deepEqual((await until(env, (x) => x.ev === 'ack')).ack, 3);
  env.Msg.emit('remove', msg('R3'));
  assert.equal((await until(env, (x) => x.ev === 'revoke_me')).id, `false_${CHAT}_R3`);

  const e = msg('E1', { body: 'new text' });
  env.Msg.emit('change:body', e, 'new text', 'old text');
  const ed = await until(env, (x) => x.ev === 'edit');
  assert.equal(ed.newBody, 'new text');
  assert.equal(ed.prevBody, 'old text');
  assert.equal(ed.blob, false);
  // Thumbnail updates: flag only, never the image.
  const big = 'A'.repeat(300);
  env.Msg.emit('change:body', msg('E2', { type: 'image', body: big }), big, big.replace('A', 'B'));
  const thumb = await until(env, (x) => x.ev === 'edit' && x.msg.id.id === 'E2');
  assert.equal(thumb.blob, true);
  assert.equal(thumb.newBody, null);
  assert.equal(thumb.prevBody, null);

  env.Chat.emit('remove', { id: new Wid(CHAT) });
  assert.equal((await until(env, (x) => x.ev === 'chat_removed')).id, CHAT);
  env.Chat.emit('change:archive', { id: new Wid(CHAT) }, true, false);
  assert.equal((await until(env, (x) => x.ev === 'chat_archived')).archived, true);
});

test('ciphertext placeholders: logged, resend requested, logged again once decrypted', async () => {
  const env = await linked();
  const m = msg('C1', { type: 'ciphertext', body: undefined });
  env.Msg.add(m);
  const first = await until(env, (x) => x.ev === 'message');
  assert.equal(first.msg.type, 'ciphertext');
  m.type = 'chat';
  m.body = 'decrypted';
  m.emit('change:type', m);
  const second = await until(env, (x) => x.ev === 'message' && x.msg.type === 'chat');
  assert.equal(second.msg.body, 'decrypted');
});

test('reactions are observed without breaking the original function', async () => {
  const env = await linked();
  const ret = env.modules.WAWebAddonReactionTableMode.reactionTableMode.bulkUpsert([
    { reactionParentKey: new MsgKey({ fromMe: true, remote: CHAT, id: 'P1', self: 'out' }), author: new Wid('x@c.us'), reactionText: '👍', reactionTimestamp: 1_700_000_001_000 },
    { reactionParentKey: new MsgKey({ fromMe: true, remote: CHAT, id: 'P1', self: 'out' }), from: new Wid('y@c.us'), reactionText: '', reactionTimestamp: 1_700_000_002_000 },
  ]);
  assert.equal(ret, 2);
  const r = await until(env, (x) => x.ev === 'reactions');
  assert.deepEqual(r.reactions, [
    { msgId: `true_${CHAT}_P1_out`, senderId: 'x@c.us', reaction: '👍', timestamp: 1_700_000_001_000 },
    { msgId: `true_${CHAT}_P1_out`, senderId: 'y@c.us', reaction: '', timestamp: 1_700_000_002_000 },
  ]);
});

test('page API: chats, contacts (LID → phone), history with loadEarlierMsgs, avatars', async () => {
  const env = await linked();
  const chat = { id: new Wid(CHAT), formattedTitle: 'Ali', t: 1_700_000_000, archive: false, pin: 1, mute: { expiration: 5 } };
  chat.msgs = { getModelsArray: () => [msg('H3', { t: 3 }), msg('N1', { t: 4, isNotification: true })] };
  env.Chat.models.set(CHAT, chat);
  env.Chat.models.set('broken', { get id() { throw new Error('broken model'); } });
  const chats = await call(env, 'listChats');
  assert.deepEqual(chats, [{ id: CHAT, name: 'Ali', timestamp: 1_700_000_000_000, archived: false, pinned: true, muted: true }]);
  assert.deepEqual(await call(env, 'chatInfo', { id: CHAT }), { name: 'Ali', timestamp: 1_700_000_000_000, archived: false, pinned: true, muted: true });
  assert.equal(await call(env, 'chatInfo', { id: 'nope@c.us' }), null);

  env.Contact.models.set('905552222222@c.us', { name: 'Veli', pushname: 'veli', isBusiness: true });
  assert.deepEqual(await call(env, 'contactInfo', { id: '222@lid' }), { name: 'Veli', pushname: 'veli', phone: '+905552222222', isMe: false, isBusiness: true });
  assert.equal(await call(env, 'contactInfo', { id: 'unknown@lid' }), null);

  env.earlier.set(CHAT, [msg('H1', { t: 1 }), msg('H2', { t: 2 })]);
  const hist = await call(env, 'fetchMessages', { id: CHAT, limit: 10 });
  assert.deepEqual(hist.map((m) => m.id.id), ['H1', 'H2', 'H3']);
  const two = await call(env, 'fetchMessages', { id: CHAT, limit: 2 });
  assert.deepEqual(two.map((m) => m.id.id), ['H3']);

  assert.equal(await call(env, 'profilePicUrl', { id: CHAT }), 'https://pps.whatsapp.net/v/pic.jpg');
});

test('media: prepare in the page, pull base64 slices, release; statuses for the unavailable cases', async () => {
  const env = await linked();
  const bytes = new Uint8Array(5000).map((_, i) => i % 256);
  env.media.set('/v/ok', bytes);
  const good = msg('M1', { type: 'image', directPath: '/v/ok', mediaData: { mediaStage: 'RESOLVED' }, size: 5000, mimetype: 'image/jpeg', filename: 'a.jpg' });
  env.Msg.models.set(`false_${CHAT}_M1`, good);
  const prep = await call(env, 'mediaPrepare', { id: `false_${CHAT}_M1`, max: 1_000_000 });
  assert.equal(prep.status, 'ok');
  assert.equal(prep.size, 5000);
  assert.equal(prep.mimetype, 'image/jpeg');
  const a = Buffer.from(await call(env, 'mediaSlice', { token: prep.token, offset: 0, length: 3000 }), 'base64');
  const b = Buffer.from(await call(env, 'mediaSlice', { token: prep.token, offset: 3000, length: 3000 }), 'base64');
  assert.deepEqual(new Uint8Array(Buffer.concat([a, b])), bytes);
  await call(env, 'mediaRelease', { token: prep.token });
  assert.equal(await call(env, 'mediaSlice', { token: prep.token, offset: 0, length: 10 }), null);

  assert.equal((await call(env, 'mediaPrepare', { id: 'missing', max: 10 })).status, 'unavailable');
  env.Msg.models.set('big', msg('B', { type: 'video', directPath: '/v/x', mediaData: { mediaStage: 'RESOLVED' }, size: 50 }));
  assert.equal((await call(env, 'mediaPrepare', { id: 'big', max: 10 })).status, 'too_large');
  env.Msg.models.set('vo', msg('V', { type: 'image', isViewOnce: true, directPath: '/v/x', mediaData: {} }));
  assert.equal((await call(env, 'mediaPrepare', { id: 'vo', max: 10 })).status, 'view_once');
  env.Msg.models.set('re', msg('RE', { type: 'image', directPath: '/v/x', mediaData: { mediaStage: 'REUPLOADING' } }));
  assert.equal((await call(env, 'mediaPrepare', { id: 're', max: 1e9 })).status, 'retry');
  env.Msg.models.set('gone', msg('GO', { type: 'image', directPath: '/v/gone', mediaData: { mediaStage: 'RESOLVED' } }));
  assert.deepEqual(await call(env, 'mediaPrepare', { id: 'gone', max: 1e9 }), { status: 'unavailable', reason: 'gone' });
});

test('commands: unknown ops and calls before WhatsApp Web loaded fail cleanly; logout', async () => {
  const env = makeWindow();
  load(env);
  await assert.rejects(call(env, 'listChats'), /not loaded yet/);
  await assert.rejects(call(env, 'constructor'), /unknown op/);
  await assert.rejects(call(env, '__proto__'), /unknown op/);
  env.ready();
  await until(env, (m) => m.t === 'wa');
  assert.equal(await call(env, 'ping'), 'loading');
  env.Socket.hasSynced = true;
  env.Socket.emit('change:hasSynced');
  await until(env, (m) => m.t === 'ready');
  assert.equal(await call(env, 'ping'), 'ok');
  await call(env, 'logout');
  assert.equal(env.Socket.loggedOut, true);
  env.Cmd.emit('logout');
  await until(env, (m) => m.t === 'logout');
});

test('CONFLICT: the session is taken back', async () => {
  const env = await linked();
  env.Socket.state = 'CONFLICT';
  env.Socket.emit('change:state', env.Socket, 'CONFLICT');
  await until(env, (m) => m.t === 'state' && m.state === 'CONFLICT');
  await tick(5200);
  assert.equal(env.Socket.tookOver, true);
});
