import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import path from "node:path";

/**
 * Lightweight Cloudflare D1 adapter backed by real in-memory SQLite (`node:sqlite`),
 * executing exact project migrations and triggers during Vitest integration tests.
 */
export function createTestD1(): D1Database {
  const sqlite = new DatabaseSync(":memory:");
  const migrationsDir = path.resolve(__dirname, "../../migrations");
  const files = fs.readdirSync(migrationsDir).filter((f) => f.endsWith(".sql")).sort();
  for (const file of files) {
    const sql = fs.readFileSync(path.join(migrationsDir, file), "utf8");
    sqlite.exec(sql);
  }

  function makeStatement(query: string, bound: unknown[] = []): D1PreparedStatement {
    return {
      bind(...values: unknown[]) {
        return makeStatement(query, values.map((v) => (v === undefined ? null : v)));
      },
      async first<T = Record<string, unknown>>(colName?: string): Promise<T | null> {
        const stmt = sqlite.prepare(query);
        const row = stmt.get(...(bound as (string | number | bigint | Uint8Array | null)[])) as
          | Record<string, unknown>
          | undefined;
        if (!row) return null;
        if (colName) return (row[colName] as T) ?? null;
        return row as T;
      },
      async all<T = Record<string, unknown>>(): Promise<D1Result<T>> {
        const stmt = sqlite.prepare(query);
        const rows = stmt.all(...(bound as (string | number | bigint | Uint8Array | null)[])) as T[];
        return {
          results: rows,
          success: true,
          meta: {
            duration: 0,
            size_after: 0,
            rows_read: rows.length,
            rows_written: 0,
            last_row_id: 0,
            changed_db: false,
            changes: 0,
          },
        };
      },
      async run(): Promise<D1Result> {
        const stmt = sqlite.prepare(query);
        const info = stmt.run(...(bound as (string | number | bigint | Uint8Array | null)[]));
        return {
          results: [],
          success: true,
          meta: {
            duration: 0,
            size_after: 0,
            rows_read: 0,
            rows_written: Number(info.changes),
            last_row_id: Number(info.lastInsertRowid),
            changed_db: Number(info.changes) > 0,
            changes: Number(info.changes),
          },
        };
      },
      async raw<T = unknown[]>(): Promise<T[]> {
        return [];
      },
    } as unknown as D1PreparedStatement;
  }

  return {
    prepare(query: string) {
      return makeStatement(query);
    },
    async batch(statements: D1PreparedStatement[]) {
      const results = [];
      for (const s of statements) {
        results.push(await s.run());
      }
      return results;
    },
    async exec(query: string) {
      sqlite.exec(query);
      return { count: 1, duration: 0 };
    },
    async dump() {
      return new ArrayBuffer(0);
    },
    withSession() {
      return this as unknown as D1DatabaseSession;
    },
  } as unknown as D1Database;
}
