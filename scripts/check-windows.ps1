$ErrorActionPreference = 'Continue'
Write-Host 'CoreLink Windows prerequisite check' -ForegroundColor Cyan
foreach ($tool in @('node', 'npm', 'rustc', 'cargo', 'adb', 'scrcpy')) {
  $cmd = Get-Command $tool -ErrorAction SilentlyContinue
  if ($cmd) { Write-Host "[OK] $tool -> $($cmd.Source)" -ForegroundColor Green }
  else { Write-Host "[MISSING] $tool" -ForegroundColor Yellow }
}
Write-Host '\nFor Android: enable Samsung Wireless debugging and pair explicitly in CoreLink.'
Write-Host 'For the Windows installer: run npm install, npm test, then npm run desktop:build.'
