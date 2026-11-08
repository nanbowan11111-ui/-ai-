$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location -LiteralPath $projectRoot
try {
    $sourceFile = Join-Path $projectRoot 'data\office.mv.db'
    if (-not (Test-Path -LiteralPath $sourceFile)) { throw '找不到 H2 数据文件 data/office.mv.db' }

    if (-not $env:DB_PASSWORD) {
        $envFile = Join-Path $projectRoot '.env'
        if (-not (Test-Path -LiteralPath $envFile)) { throw '找不到 .env，请先设置 DB_PASSWORD' }
        $passwordLine = Get-Content -LiteralPath $envFile | Where-Object { $_ -match '^DB_PASSWORD=' } | Select-Object -Last 1
        if (-not $passwordLine) { throw '.env 中没有 DB_PASSWORD' }
        $env:DB_PASSWORD = ($passwordLine -split '=', 2)[1]
    }
    if (-not $env:DB_PASSWORD) { throw 'DB_PASSWORD 不能为空' }

    # 复制源文件后再建立目标连接，保留可独立恢复的原始数据。
    $backupDir = Join-Path $projectRoot 'data\backups'
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    $backup = Join-Path $backupDir ('office-h2-before-mysql-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.mv.db')
    Copy-Item -LiteralPath $sourceFile -Destination $backup -ErrorAction Stop
    Write-Output "H2 备份：$backup"

    $classpathFile = Join-Path $projectRoot 'backend\target\migration-classpath.txt'
    & mvn -q -f 'backend/pom.xml' dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0) { throw '无法解析迁移所需的 JDBC 驱动' }
    $classpath = (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    if (-not $classpath) { throw 'JDBC classpath 为空' }
    & java --class-path $classpath 'backend/scripts/MigrateH2ToMySql.java'
    if ($LASTEXITCODE -ne 0) { throw '迁移失败；请检查上述错误，原 H2 数据和备份仍在' }
} finally {
    Pop-Location
}
