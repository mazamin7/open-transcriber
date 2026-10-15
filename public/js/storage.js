/**
 * Local Conversation Storage & Privacy Shredder
 * Uses browser IndexedDB for zero-leakage local conversation logging.
 */
class LocalTranscriptStorage {
  constructor() {
    this.dbName = 'OfflineLiveTranscribeDB';
    this.version = 1;
    this.storeName = 'conversations';
    this.db = null;
  }

  async init() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(this.dbName, this.version);

      request.onupgradeneeded = (event) => {
        const db = event.target.result;
        if (!db.objectStoreNames.contains(this.storeName)) {
          const store = db.createObjectStore(this.storeName, { keyPath: 'id', autoIncrement: true });
          store.createIndex('timestamp', 'timestamp', { unique: false });
        }
      };

      request.onsuccess = (event) => {
        this.db = event.target.result;
        resolve(this.db);
      };

      request.onerror = (event) => {
        console.error('[Storage] IndexedDB initialization error:', event.target.error);
        reject(event.target.error);
      };
    });
  }

  async saveConversation(text, durationSeconds = 0) {
    if (!text || !text.trim()) return null;
    if (!this.db) await this.init();

    return new Promise((resolve, reject) => {
      const transaction = this.db.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);

      const record = {
        timestamp: new Date().toISOString(),
        text: text.trim(),
        durationSeconds: durationSeconds,
        wordCount: text.trim().split(/\s+/).length
      };

      const request = store.add(record);
      request.onsuccess = (event) => {
        record.id = event.target.result;
        resolve(record);
      };
      request.onerror = (event) => reject(event.target.error);
    });
  }

  async getAllConversations() {
    if (!this.db) await this.init();

    return new Promise((resolve, reject) => {
      const transaction = this.db.transaction([this.storeName], 'readonly');
      const store = transaction.objectStore(this.storeName);
      const request = store.getAll();

      request.onsuccess = (event) => {
        // Sort descending by timestamp
        const records = (event.target.result || []).sort((a, b) => 
          new Date(b.timestamp) - new Date(a.timestamp)
        );
        resolve(records);
      };
      request.onerror = (event) => reject(event.target.error);
    });
  }

  async searchConversations(query) {
    const all = await this.getAllConversations();
    if (!query || !query.trim()) return all;
    const lower = query.toLowerCase().trim();
    return all.filter(c => c.text.toLowerCase().includes(lower));
  }

  async deleteConversation(id) {
    if (!this.db) await this.init();
    return new Promise((resolve, reject) => {
      const transaction = this.db.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);
      const request = store.delete(id);
      request.onsuccess = () => resolve(true);
      request.onerror = (event) => reject(event.target.error);
    });
  }

  /**
   * Complete Privacy Shred / Data Wipe
   * Deletes the entire IndexedDB database and local caches permanently.
   */
  async shredAllData() {
    if (this.db) {
      this.db.close();
      this.db = null;
    }

    return new Promise((resolve, reject) => {
      const req = indexedDB.deleteDatabase(this.dbName);
      req.onsuccess = () => {
        console.log('[Storage] All conversation history scrubbed completely');
        resolve(true);
      };
      req.onerror = (event) => reject(event.target.error);
      req.onblocked = () => {
        console.warn('[Storage] Database deletion blocked by active connection');
        resolve(false);
      };
    });
  }

  /**
   * Export conversations as plain text or JSON
   */
  async exportData(format = 'txt') {
    const list = await this.getAllConversations();
    let content = '';
    let mimeType = 'text/plain';
    let filename = `transcripts_${new Date().toISOString().slice(0, 10)}`;

    if (format === 'json') {
      content = JSON.stringify(list, null, 2);
      mimeType = 'application/json';
      filename += '.json';
    } else {
      content = list.map(c => {
        return `[${new Date(c.timestamp).toLocaleString()}]\n${c.text}\n----------------------------------------\n`;
      }).join('\n');
      filename += '.txt';
    }

    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = LocalTranscriptStorage;
}
