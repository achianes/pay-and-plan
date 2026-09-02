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
  maxUploadMb: Number(process.env.MAX_UPLOAD_MB || fileConfig.maxUploadMb || 25)
}
