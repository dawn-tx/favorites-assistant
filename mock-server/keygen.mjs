// 生成 mock 同步服务用的 RSA-2048 密钥对（OAEP-SHA-256）。
//
//   node mock-server/keygen.mjs
//
// 产物：
//   mock-server/server_private_key.pem  服务端私钥，server.mjs 自动加载，用于解开信封里的会话密钥
//   mock-server/server_public_key.pem   公钥，整段粘贴进 App「设置 → 服务端 RSA 公钥」即可
//
// 公钥也可以在打包时固化：./gradlew assembleDebug -PSERVER_RSA_PUBLIC_KEY_PEM="$(cat ...)"
// 或直接设置环境变量 SYNC_SERVER_PUBLIC_KEY（CI 里用 Secrets）。

import { generateKeyPairSync } from 'node:crypto';
import { existsSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = dirname(fileURLToPath(import.meta.url));
const PRIVATE_PATH = join(ROOT, 'server_private_key.pem');
const PUBLIC_PATH = join(ROOT, 'server_public_key.pem');

if (existsSync(PRIVATE_PATH)) {
  console.log(`已存在 ${PRIVATE_PATH}，如需重新生成请先删除（否则历史密文将无法解密）。`);
  process.exit(0);
}

const { privateKey, publicKey } = generateKeyPairSync('rsa', {
  modulusLength: 2048,
  publicKeyEncoding: { type: 'spki', format: 'pem' },
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
});

writeFileSync(PRIVATE_PATH, privateKey);
writeFileSync(PUBLIC_PATH, publicKey);

console.log(`私钥: ${PRIVATE_PATH}`);
console.log(`公钥: ${PUBLIC_PATH}`);
console.log('\n把下面这段整块粘贴到 App 设置里的「服务端 RSA 公钥（PEM）」：\n');
console.log(publicKey);
