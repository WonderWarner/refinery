/*
 * SPDX-FileCopyrightText: 2023-2024 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */

import { Zstd } from '@hpcc-js/wasm-zstd';

import type {
  CompressResponse,
  CompressorRequest,
  DecompressResponse,
  ErrorResponse,
} from './compressionMessages';

const CONTENT_TYPE = 'application/octet-stream';

const URI_PREFIX = `data:${CONTENT_TYPE};base64,`;

async function base64Encode(buffer: Uint8Array): Promise<string> {
  const uri = await new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error ?? new Error('Unknown error'));
    reader.readAsDataURL(new File([buffer], '', { type: CONTENT_TYPE }));
  });
  if (typeof uri !== 'string') {
    throw new Error(`Unexpected FileReader result type: ${typeof uri}`);
  }
  const base64 = uri.substring(URI_PREFIX.length);
  return base64.replace(/[+/]/g, (c) => {
    if (c === '+') {
      return '-';
    }
    if (c === '/') {
      return '_';
    }
    return c;
  });
}

async function base64Decode(compressedText: string): Promise<Uint8Array> {
  const base64 = compressedText.replace(/[-_]/g, (c) => {
    if (c === '-') {
      return '+';
    }
    if (c === '_') {
      return '/';
    }
    return c;
  });
  const result = await fetch(`${URI_PREFIX}${base64}`);
  return new Uint8Array(await result.arrayBuffer());
}

let zstd: Zstd | undefined;

globalThis.onmessage = (event) => {
  (async () => {
    zstd ??= await Zstd.load();
    // Since the render thread will only send us valid messages,
    // we can save a bit of bundle size by using a cast instead of `parse`
    // to avoid having to include `zod` in the worker.
    const message = event.data as CompressorRequest;
    if (message.request === 'compress') {
      const encoder = new TextEncoder();
      const encodedBuffer = encoder.encode(message.text);
      const compressedBuffer = zstd.compress(encodedBuffer, 3);
      const compressedText = await base64Encode(compressedBuffer);
      globalThis.postMessage({
        response: 'compressed',
        compressedText,
        version: message.version,
      } satisfies CompressResponse);
    } else if (message.request === 'decompress') {
      const decodedBuffer = await base64Decode(message.compressedText);
      const uncompressedBuffer = zstd.decompress(decodedBuffer);
      const decoder = new TextDecoder();
      const text = decoder.decode(uncompressedBuffer);
      globalThis.postMessage({
        response: 'decompressed',
        text,
        version: message.version,
      } satisfies DecompressResponse);
    } else {
      throw new Error(`Unknown request: ${JSON.stringify(event.data)}`);
    }
  })().catch((error) => {
    globalThis.postMessage({
      response: 'error',
      message: String(error),
    } satisfies ErrorResponse);
  });
};
