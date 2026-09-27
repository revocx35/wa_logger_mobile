// A small fake of the WhatsApp Web internals walm-bridge.js touches (window.require modules, Backbone-like
// collections and models), so the bridge can be tested in Node without WhatsApp. Shapes follow what the
// wa_logger server's code and whatsapp-web.js rely on (see docs/wwebjs-notes.md in wa_logger).
import fs from 'node:fs';
import vm from 'node:vm';

export class Emitter {
  constructor() {
    this.handlers = new Map();
  }
  on(names, fn) {
    for (const n of names.split(' ')) {
      if (!this.handlers.has(n)) this.handlers.set(n, []);
      this.handlers.get(n).push({ fn, once: false });
    }
  }
  once(name, fn) {
    if (!this.handlers.has(name)) this.handlers.set(name, []);
    this.handlers.get(name).push({ fn, once: true });
  }
  off(name, fn) {
    this.handlers.set(name, (this.handlers.get(name) || []).filter((h) => h.fn !== fn));
  }
  emit(name, ...args) {
    const list = this.handlers.get(name) || [];
    this.handlers.set(name, list.filter((h) => !h.once));
    for (const h of list) h.fn(...args);
  }
}

export class Wid {
  constructor(s) {
    this._s = s;
    this.server = s.split('@')[1];
    this.user = s.split('@')[0];
  }
  toString() {
    return this._s;
  }
  // Current WhatsApp Web builds don't expose _serialized on some ids; the bridge must cope.
}

export class MsgKey {
  constructor({ fromMe, remote, id, participant, self }) {
    this.fromMe = fromMe;
    this.remote = new Wid(remote);
    this.id = id;
    if (participant) this.participant = new Wid(participant);
    if (self) this.self = self;
  }
  toString() {
    return `${this.fromMe}_${this.remote}_${this.id}${this.participant ? `_${this.participant}` : ''}${this.self ? `_${this.self}` : ''}`;
  }
}

export class MsgModel extends Emitter {
  constructor(attrs) {
    super();
    Object.assign(this, attrs);
  }
  serialize() {
    const out = {};
    for (const [k, v] of Object.entries(this)) if (k !== 'handlers' && typeof v !== 'function') out[k] = v;
    // serialize() keeps Wid/MsgKey objects as they are.
    return out;
  }
}

export class Collection extends Emitter {
  constructor() {
    super();
    this.models = new Map();
  }
  add(m, key = String(m.id)) {
    this.models.set(key, m);
    this.emit('add', m);
  }
  get(id) {
    return this.models.get(String(id));
  }
  getModelsArray() {
    return [...this.models.values()];
  }
}

export function makeWindow({ state = 'OPENING', synced = false } = {}) {
  const posted = [];
  const listeners = [];
  const port = {
    postMessage: (s) => posted.push(typeof s === 'string' ? JSON.parse(s) : s),
    addEventListener: (type, fn) => type === 'message' && listeners.push(fn),
  };
  const Socket = Object.assign(new Emitter(), {
    state,
    hasSynced: synced,
    takeover() {
      this.tookOver = true;
    },
    async logout() {
      this.loggedOut = true;
    },
  });
  const Conn = Object.assign(new Emitter(), { ref: 'REF1', pushname: 'Owner' });
  const Cmd = Object.assign(new Emitter(), {
    refreshQR() {
      Conn.ref = 'REF-refreshed';
    },
  });
  const Msg = new Collection();
  Msg.getMessagesById = async (ids) => ({ messages: ids.map((i) => Msg.get(i)).filter(Boolean) });
  const Chat = new Collection();
  const Contact = new Collection();
  const ProfilePicThumb = new Collection();
  const earlier = new Map(); // chatId → older messages loadEarlierMsgs returns (once)
  const media = new Map(); // directPath → Uint8Array
  const modules = {
    WAWebSocketModel: { Socket },
    WAWebConnModel: { Conn },
    WAWebCmd: { Cmd },
    WAWebCollections: { Msg, Chat, Contact, ProfilePicThumb },
    WAWebMiscBrowserUtils: { info: () => ({ os: 'Linux', name: 'Chrome', ua: 'x' }) },
    WAWebCompanionRegClientUtils: { DEVICE_PLATFORM: 'Chrome' },
    WAWebAdvSignatureApi: {},
    WAWebUserPrefsInfoStore: { waNoiseInfo: { get: async () => ({ staticKeyPair: { pubKey: 'NOISE' } }) } },
    WAWebSignalStoreApi: { waSignalStore: { getRegistrationInfo: async () => ({ identityKeyPair: { pubKey: 'IDENT' } }) } },
    WABase64: { encodeB64: (x) => Buffer.from(String(x)).toString('base64') },
    WAWebUserPrefsMultiDevice: { getADVSecretKey: () => 'ADV' },
    WAWebUserPrefsMeUser: { getMaybeMePnUser: () => new Wid('905550000001@c.us'), getMaybeMeLidUser: () => new Wid('111@lid') },
    WAWebWidFactory: { createWid: (s) => new Wid(s) },
    WAWebLidMigrationUtils: { toPn: (w) => (String(w) === '222@lid' ? new Wid('905552222222@c.us') : null) },
    WAWebFindChatAction: { findOrCreateLatestChat: async (w) => ({ chat: Chat.get(String(w)) }) },
    WAWebChatLoadMessages: {
      loadEarlierMsgs: async ({ chat }) => {
        const list = earlier.get(String(chat.id)) || [];
        earlier.delete(String(chat.id));
        return list;
      },
    },
    WAWebContactProfilePicThumbBridge: { requestProfilePicFromServer: async () => ({ eurl: 'https://pps.whatsapp.net/v/pic.jpg' }) },
    WAWebDownloadManager: {
      downloadManager: {
        downloadAndMaybeDecrypt: async ({ directPath }) => {
          const data = media.get(directPath);
          if (!data) throw Object.assign(new Error('gone'), { status: 404 });
          return data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength);
        },
      },
    },
    WAWebAltDeviceLinkingApi: {
      setPairingType(t) {
        this.type = t;
      },
      async initializeAltDeviceLinking() {},
      async startAltLinkingFlow(phone) {
        this.phone = phone;
        return 'ABCD1234';
      },
      async initializeQRLinking() {
        this.qr = true;
      },
    },
    WAWebLaunchSocketUtils: { refreshQR() {} },
    WAWebSyncGatingUtils: { isPlaceholderMessageResendEnabled: () => false },
    WAWebNonMessageDataRequestPlaceholderMessageResendUtils: {
      handlePlaceholderMsgsSeen(msgs) {
        this.requested = (this.requested || 0) + msgs.length;
      },
    },
    WAWebOfflineHandler: { OfflineMessageHandler: { getOfflineDeliveryProgress: () => 42 } },
    WAWebAddonReactionTableMode: { reactionTableMode: { bulkUpsert: (list) => list.length } },
    WAWebPresenceChatAction: {
      sent: 0,
      sendPresenceUnavailable() {
        this.sent++;
        return Promise.resolve();
      },
    },
  };

  class FileReader {
    readAsDataURL(blob) {
      blob.arrayBuffer().then((ab) => {
        this.result = `data:application/octet-stream;base64,${Buffer.from(ab).toString('base64')}`;
        this.onload && this.onload();
      });
    }
  }

  const win = {
    WalBridge: port,
    Debug: undefined,
    setTimeout,
    clearTimeout,
    // The bridge keeps some intervals for the page's lifetime; they must not keep the test process alive.
    setInterval: (...a) => {
      const t = setInterval(...a);
      t.unref();
      return t;
    },
    clearInterval,
    Blob,
    FileReader,
    AbortController,
  };
  win.window = win;
  win.top = win;
  const ctx = vm.createContext(win);
  // WhatsApp Web appears after the bridge (document start): call ready() to define its module system.
  const ready = (version = '2.3000.1048599391') => {
    win.Debug = { VERSION: version };
    win.require = (name) => {
      if (!(name in modules)) throw new Error(`Requiring unknown module "${name}"`);
      return modules[name];
    };
  };
  const send = (cmd) => listeners.forEach((fn) => fn({ data: JSON.stringify(cmd) }));
  return { win, ctx, posted, send, ready, modules, Socket, Conn, Cmd, Msg, Chat, Contact, earlier, media };
}

export const BRIDGE = fs.readFileSync(new URL('../app/src/main/assets/walm-bridge.js', import.meta.url), 'utf8');

export function load(env) {
  vm.runInContext(BRIDGE, env.ctx, { filename: 'walm-bridge.js' });
}

export const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

/** Waits until a posted message matches. */
export async function until(env, pred, ms = 3000) {
  const start = Date.now();
  for (;;) {
    const hit = env.posted.find(pred);
    if (hit) return hit;
    if (Date.now() - start > ms) throw new Error('timed out waiting for a bridge message');
    await tick(10);
  }
}

/** Sends a command and waits for its result. */
let nextId = 1;
export async function call(env, op, args = {}) {
  const id = nextId++;
  env.send({ id, op, args });
  const res = await until(env, (m) => m.t === 'res' && m.id === id);
  if (!res.ok) throw new Error(res.error);
  return res.data;
}
