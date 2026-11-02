import fs from 'node:fs'
import path from 'node:path'

/**
 * Configuration comes from the environment, or from a JSON file when the process is
 * launched by the Windows scheduler (a task action cannot carry environment variables,
 * and routing it through cmd.exe breaks on paths containing "&").
 */
const CONFIG_PATH = process.env.PAYPLAN_CONFIG ||
  (process.platform === 'win32' ? 'C:/ProgramData/PayAndPlan/config.json' : '')

let fileConfig = {}
if (CONFIG_PATH && fs.existsSync(CONFIG_PATH)) {
  try {
    // PowerShell writes UTF-8 with a BOM, which JSON.parse refuses
    fileConfig = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8').replace(/^﻿/, ''))
  } catch (e) {
    console.error(`ignoring unreadable ${CONFIG_PATH}: ${e.message}`)
  }
}

export const config = {
  port: Number(process.env.PORT || fileConfig.port || 8080),
  dataDir: process.env.DATA_DIR || fileConfig.dataDir || path.join(process.cwd(), 'data'),
  jwtSecret: process.env.JWT_SECRET || fileConfig.jwtSecret || 'change-me-please',
  maxUploadMb: Number(process.env.MAX_UPLOAD_MB || fileConfig.maxUploadMb || 25),
  // an Ollama server with a vision model turns till receipts into shopping lists;
  // leave the URL empty and the feature is simply off, clients do not even show it
  ollamaUrl: (process.env.OLLAMA_URL || fileConfig.ollamaUrl || '').trim(),
  ollamaModel: process.env.OLLAMA_MODEL || fileConfig.ollamaModel || 'qwen3-vl:8b',
  // Enable Banking reads the account itself, under PSD2. The application id and the private
  // key stay here and are never sent to a phone; with neither of them the feature is off
  enableBankingAppId: (process.env.ENABLE_BANKING_APP_ID || fileConfig.enableBankingAppId || '').trim(),
  enableBankingKeyPath: (
    process.env.ENABLE_BANKING_KEY ||
    fileConfig.enableBankingKeyPath ||
    path.join(process.env.DATA_DIR || fileConfig.dataDir || process.cwd(), 'enablebanking.pem')
  ),
  // where a bank sends the owner back after they have said yes, from outside this house
  publicUrl: (process.env.PUBLIC_URL || fileConfig.publicUrl || '').replace(/\/$/, ''),
  // The Android build is handed out on the tailnet and nowhere else, so it gets a listener
  // of its own bound to the tailnet addresses: the public tunnel points at the main port
  // and cannot reach this one. Set the port to 0 and the handout is off.
  apkPort: Number(process.env.APK_PORT ?? fileConfig.apkPort ?? 8100),
  apkDir: process.env.APK_DIR || fileConfig.apkDir ||
    path.join(process.env.DATA_DIR || fileConfig.dataDir || process.cwd(), 'apk')
}
