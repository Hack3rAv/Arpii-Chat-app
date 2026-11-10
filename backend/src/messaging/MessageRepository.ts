export interface EncryptedMessageDto {
  id: string;
  senderUserId: string;
  senderDeviceId: number;
  recipientUserId: string;
  messageType: string;
  ciphertextB64: string;
  replyToMessageId: string | null;
  disappearsAt: number | null;
  isEdited: boolean;
  isDeletedForEveryone: boolean;
  serverSeq: number;
  createdAt: number;
  updatedAt: number;
  receiptStatus: string;
}

interface RawMessageJoinRow {
  id: string;
  sender_user_id: string;
  sender_device_id: number;
  recipient_user_id: string;
  message_type: string;
  ciphertext_b64: string;
  reply_to_message_id: string | null;
  disappears_at: number | null;
  is_edited: number;
  is_deleted_for_everyone: number;
  server_seq: number;
  created_at: number;
  updated_at: number;
  receipt_status: string | null;
}

function toDto(row: RawMessageJoinRow): EncryptedMessageDto {
  return {
    id: row.id,
    senderUserId: row.sender_user_id,
    senderDeviceId: row.sender_device_id,
    recipientUserId: row.recipient_user_id,
    messageType: row.message_type,
    ciphertextB64: row.ciphertext_b64,
    replyToMessageId: row.reply_to_message_id,
    disappearsAt: row.disappears_at,
    isEdited: row.is_edited === 1,
    isDeletedForEveryone: row.is_deleted_for_everyone === 1,
    serverSeq: row.server_seq,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    receiptStatus: row.receipt_status ?? "SENT",
  };
}

export class MessageRepository {
  constructor(private readonly db: D1Database) {}

  private async ensurePrimaryConversation(): Promise<void> {
    const existing = await this.db
      .prepare("SELECT id FROM conversations WHERE id = 'primary_pair' LIMIT 1")
      .first<{ id: string }>();
    if (existing) return;

    const users = await this.db
      .prepare("SELECT id FROM users ORDER BY slot_id ASC LIMIT 2")
      .all<{ id: string }>();
    const list = users.results ?? [];
    if (list.length < 2) {
      throw new Error("Both pair accounts must exist before creating conversation");
    }

    await this.db
      .prepare(
        `INSERT OR IGNORE INTO conversations (id, user_one_id, user_two_id, created_at)
         VALUES ('primary_pair', ?, ?, ?)`
      )
      .bind(list[0]!.id, list[1]!.id, Date.now())
      .run();
  }

  private async nextSeq(): Promise<number> {
    const row = await this.db
      .prepare("SELECT COALESCE(MAX(server_seq), 0) + 1 as nextSeq FROM encrypted_messages")
      .first<{ nextSeq: number }>();
    return row?.nextSeq ?? 1;
  }

  async findMessageById(id: string): Promise<EncryptedMessageDto | null> {
    const row = await this.db
      .prepare(
        `SELECT m.*, r.status as receipt_status
         FROM encrypted_messages m
         LEFT JOIN message_receipts r ON r.message_id = m.id AND r.recipient_user_id = m.recipient_user_id
         WHERE m.id = ?
         LIMIT 1`
      )
      .bind(id)
      .first<RawMessageJoinRow>();
    return row ? toDto(row) : null;
  }

  async saveEncryptedMessage(input: {
    id: string;
    senderUserId: string;
    senderDeviceId: number;
    recipientUserId: string;
    messageType: string;
    ciphertextB64: string;
    replyToMessageId?: string | null;
    disappearsAt?: number | null;
    now: number;
  }): Promise<{ message: EncryptedMessageDto; deduplicated: boolean }> {
    const existing = await this.findMessageById(input.id);
    if (existing) {
      return { message: existing, deduplicated: true };
    }

    await this.ensurePrimaryConversation();
    const seq = await this.nextSeq();

    await this.db
      .prepare(
        `INSERT INTO encrypted_messages (
          id, conversation_id, sender_user_id, sender_device_id, recipient_user_id,
          message_type, ciphertext_b64, envelope_version, reply_to_message_id, disappears_at,
          is_edited, is_deleted_for_everyone, server_seq, created_at, updated_at
        ) VALUES (?, 'primary_pair', ?, ?, ?, ?, ?, 1, ?, ?, 0, 0, ?, ?, ?)`
      )
      .bind(
        input.id,
        input.senderUserId,
        input.senderDeviceId,
        input.recipientUserId,
        input.messageType,
        input.ciphertextB64,
        input.replyToMessageId ?? null,
        input.disappearsAt ?? null,
        seq,
        input.now,
        input.now
      )
      .run();

    await this.db
      .prepare(
        `INSERT OR IGNORE INTO message_receipts (message_id, recipient_user_id, status, updated_at)
         VALUES (?, ?, 'SENT', ?)`
      )
      .bind(input.id, input.recipientUserId, input.now)
      .run();

    const saved = await this.findMessageById(input.id);
    if (!saved) {
      throw new Error("Failed to read back inserted encrypted message");
    }
    return { message: saved, deduplicated: false };
  }

  async syncAfterSeq(
    afterSeq: number,
    now: number,
    limit = 200
  ): Promise<{ messages: EncryptedMessageDto[]; maxSeq: number }> {
    // Purge expired disappearing envelopes cleanly on server
    await this.db
      .prepare("DELETE FROM encrypted_messages WHERE disappears_at IS NOT NULL AND disappears_at <= ?")
      .bind(now)
      .run();

    const rows = await this.db
      .prepare(
        `SELECT m.*, r.status as receipt_status
         FROM encrypted_messages m
         LEFT JOIN message_receipts r ON r.message_id = m.id AND r.recipient_user_id = m.recipient_user_id
         WHERE m.server_seq > ?
         ORDER BY m.server_seq ASC
         LIMIT ?`
      )
      .bind(afterSeq, limit)
      .all<RawMessageJoinRow>();

    const list = (rows.results ?? []).map(toDto);
    const maxSeq = list.length > 0 ? list[list.length - 1]!.serverSeq : afterSeq;
    return { messages: list, maxSeq };
  }

  async ackReceipts(
    messageIds: string[],
    recipientUserId: string,
    status: "DELIVERED" | "READ",
    now: number
  ): Promise<string[]> {
    const updatedIds: string[] = [];
    for (const msgId of messageIds) {
      const sql =
        status === "READ"
          ? `UPDATE message_receipts
             SET status = 'READ', updated_at = ?
             WHERE message_id = ? AND recipient_user_id = ? AND status != 'READ'`
          : `UPDATE message_receipts
             SET status = 'DELIVERED', updated_at = ?
             WHERE message_id = ? AND recipient_user_id = ? AND status = 'SENT'`;

      const res = await this.db.prepare(sql).bind(now, msgId, recipientUserId).run();
      if ((res.meta?.changes ?? 0) > 0) {
        updatedIds.push(msgId);
      }
    }
    return updatedIds;
  }

  async editMessage(
    messageId: string,
    senderUserId: string,
    ciphertextB64: string,
    now: number
  ): Promise<EncryptedMessageDto | null> {
    const existing = await this.findMessageById(messageId);
    if (!existing || existing.senderUserId !== senderUserId || existing.isDeletedForEveryone) {
      return null;
    }

    const seq = await this.nextSeq();
    await this.db
      .prepare(
        `UPDATE encrypted_messages
         SET ciphertext_b64 = ?, is_edited = 1, server_seq = ?, updated_at = ?
         WHERE id = ? AND sender_user_id = ?`
      )
      .bind(ciphertextB64, seq, now, messageId, senderUserId)
      .run();

    return this.findMessageById(messageId);
  }

  async deleteMessageForEveryone(
    messageId: string,
    senderUserId: string,
    now: number
  ): Promise<EncryptedMessageDto | null> {
    const existing = await this.findMessageById(messageId);
    if (!existing || existing.senderUserId !== senderUserId) {
      return null;
    }

    const seq = await this.nextSeq();
    await this.db
      .prepare(
        `UPDATE encrypted_messages
         SET ciphertext_b64 = '', is_deleted_for_everyone = 1, server_seq = ?, updated_at = ?
         WHERE id = ? AND sender_user_id = ?`
      )
      .bind(seq, now, messageId, senderUserId)
      .run();

    return this.findMessageById(messageId);
  }
}
