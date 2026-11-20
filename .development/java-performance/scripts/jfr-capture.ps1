#Requires -Version 5.1
<#
.SYNOPSIS
Captures JFR recordings, heap dumps and thread dumps for any Java process without editing
the project under test.

.DESCRIPTION
Four modes, all writing into an out-of-tree run directory:

  java    launch a main class under -XX:StartFlightRecording
  gradle  run a Gradle test task with JFR injected by an init script (no build file edit)
  maven   run mvn test with -DargLine carrying the recording options
  attach  attach to a running JVM by PID (jfr, heap or thread artifact)

Child output goes to files, not through a PowerShell pipeline: a Gradle or Maven daemon inherits
the redirected handle and would otherwise keep the pipeline open after the build has finished.

Every artifact is validated before the script reports success, and `<label>-run.json` records the
exact command, JDK, timings and sizes. A failing workload keeps its artifact: a failed test run
still produces a usable recording, so failure is reported through the exit code instead of
deleting evidence.

.EXAMPLE
.\jfr-capture.ps1 -Mode gradle -ProjectDir C:\work\shop -Task ':core:test' `
  -TestSelector 'OrderServiceTest#recalculates' -Label order-baseline

.EXAMPLE
.\jfr-capture.ps1 -Mode attach -TargetPid 1234 -Seconds 60 -Label live-traffic

.EXAMPLE
.\jfr-capture.ps1 -Mode attach -TargetPid 1234 -Artifact heap -Label leak-suspect
#>
[CmdletBinding(DefaultParameterSetName = 'java')]
param(
	[Parameter(Mandatory = $true)][ValidateSet('java', 'gradle', 'maven', 'attach')][string]$Mode,

	# java mode
	[string]$MainClass,
	[string]$ClassPath,
	[string[]]$AppArgs,
	[string]$ExtraJvmArgs,

	# gradle / maven mode
	[string]$ProjectDir,
	[string]$Task = 'test',
	[string]$TestSelector,

	# attach mode
	[int]$TargetPid,
	[ValidateSet('jfr', 'heap', 'thread')][string]$Artifact = 'jfr',
	[ValidateRange(1, 3600)][int]$Seconds = 30,

	[string]$OutDir = (Join-Path $env:LOCALAPPDATA 'jfr-perf'),
	[ValidatePattern('^[A-Za-z0-9_.-]+$')][string]$Label = 'capture',
	[ValidateRange(1, 120)][int]$TimeoutMinutes = 15
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Split-Path (Split-Path (Get-Command 'java.exe').Source) }
$java = Join-Path $javaHome 'bin\java.exe'
$jcmd = Join-Path $javaHome 'bin\jcmd.exe'
$jfrTool = Join-Path $javaHome 'bin\jfr.exe'
foreach ($tool in @($java, $jcmd, $jfrTool)) {
	if (-not (Test-Path -LiteralPath $tool)) { throw "JDK tool missing: $tool (set JAVA_HOME to a JDK 21+)" }
}

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$runDir = Join-Path ([IO.Path]::GetFullPath($OutDir)) "$Label-$stamp"
[void][IO.Directory]::CreateDirectory($runDir)

$recordingOptions = 'settings=profile,dumponexit=true,maxsize=256m'

function Get-QuotedArgument([string]$Value) {
	if ($Value -match '[\s"]') { return '"' + $Value.Replace('"', '\"') + '"' }
	return $Value
}

<#
Runs an external tool with stdout/stderr redirected to files and returns its exit code.
Waiting only for the launched process is what keeps a daemon from blocking the caller.
#>
function Invoke-ExternalTool {
	param(
		[string]$FilePath,
		[string[]]$Arguments,
		[string]$LogBase,
		[int]$TimeoutMs = 0
	)
	$outLog = "$LogBase.out.log"
	$errLog = "$LogBase.err.log"
	$argumentLine = ($Arguments | ForEach-Object { Get-QuotedArgument $_ }) -join ' '
	$process = Start-Process -FilePath $FilePath -ArgumentList $argumentLine -NoNewWindow -PassThru `
		-RedirectStandardOutput $outLog -RedirectStandardError $errLog
	$finished = if ($TimeoutMs -gt 0) { $process.WaitForExit($TimeoutMs) } else { $process.WaitForExit(); $true }
	if (-not $finished) {
		try { $process.Kill($true) } catch { }
		throw "timed out after ${TimeoutMs}ms: $FilePath $argumentLine"
	}
	foreach ($log in @($outLog, $errLog)) {
		if ((Test-Path -LiteralPath $log) -and (Get-Item -LiteralPath $log).Length -gt 0) {
			Get-Content -LiteralPath $log -Tail 20 | ForEach-Object { Write-Host "  $_" }
		}
	}
	return $process.ExitCode
}

function Get-JfrVerdict([string]$Path) {
	if (-not (Test-Path -LiteralPath $Path)) { return 'missing' }
	if ((Get-Item -LiteralPath $Path).Length -le 0) { return 'empty' }
	$summary = & $jfrTool summary $Path 2>&1
	if ($LASTEXITCODE -ne 0) { return "unreadable: $($summary -join ' ')" }
	return 'ok'
}

$manifest = [ordered]@{
	label        = $Label
	mode         = $Mode
	artifact     = $Artifact
	runDir       = $runDir
	startedUtc   = (Get-Date).ToUniversalTime().ToString('o')
	# String interpolation keeps stderr records out of the manifest; raw records serialize deeply.
	javaVersion  = "$(& $java '-version' 2>&1 | Select-Object -First 1)"
	targetPid    = $null
	command      = $null
	exitCode     = $null
	durationMs   = $null
	artifactPath = $null
	artifactMiB  = $null
	valid        = $false
	error        = $null
}

$exitCode = 0
$stopwatch = [Diagnostics.Stopwatch]::StartNew()

try {
	switch ($Mode) {
		'java' {
			if (-not $MainClass) { throw '-MainClass is required in java mode.' }
			$recording = Join-Path $runDir "$Label.jfr"
			$arguments = @("-XX:StartFlightRecording=filename=$recording,$recordingOptions")
			if ($ExtraJvmArgs) { $arguments += $ExtraJvmArgs.Split(' ') }
			if ($ClassPath) { $arguments += @('-cp', $ClassPath) }
			$arguments += $MainClass
			if ($AppArgs) { $arguments += $AppArgs }

			$manifest.command = "$java $($arguments -join ' ')"
			$exitCode = Invoke-ExternalTool -FilePath $java -Arguments $arguments -LogBase (Join-Path $runDir 'run') `
				-TimeoutMs ($TimeoutMinutes * 60000)
			$manifest.artifactPath = $recording
		}
		'gradle' {
			if (-not $ProjectDir) { throw '-ProjectDir is required in gradle mode.' }
			$initScript = Join-Path $PSScriptRoot 'jfr-test.init.gradle'
			if (-not (Test-Path -LiteralPath $initScript)) { throw "Missing init script: $initScript" }
			$recording = Join-Path $runDir "$Label.jfr"
			$arguments = @('-p', $ProjectDir, '-I', $initScript, "-Pjfr.out=$recording", $Task, '--rerun', '--console=plain')
			if ($TestSelector) {
				# Gradle matches Class.method, JUnit convention is Class#method: accept both.
				$arguments += @('--tests', ($TestSelector -replace '#', '.'))
			}

			$manifest.command = "gradle $($arguments -join ' ')"
			$exitCode = Invoke-ExternalTool -FilePath 'gradle' -Arguments $arguments -LogBase (Join-Path $runDir 'gradle') `
				-TimeoutMs ($TimeoutMinutes * 60000)
			$manifest.artifactPath = $recording
		}
		'maven' {
			if (-not $ProjectDir) { throw '-ProjectDir is required in maven mode.' }
			$recording = Join-Path $runDir "$Label.jfr"
			$arguments = @('-f', (Join-Path $ProjectDir 'pom.xml'), 'test',
				"-DargLine=-XX:StartFlightRecording=filename=$recording,$recordingOptions")
			if ($TestSelector) { $arguments += "-Dtest=$TestSelector" }

			$manifest.command = "mvn $($arguments -join ' ')"
			$exitCode = Invoke-ExternalTool -FilePath 'mvn' -Arguments $arguments -LogBase (Join-Path $runDir 'maven') `
				-TimeoutMs ($TimeoutMinutes * 60000)
			$manifest.artifactPath = $recording
		}
		'attach' {
			if ($TargetPid -le 0) { throw '-TargetPid is required in attach mode.' }
			$process = Get-CimInstance Win32_Process -Filter "ProcessId = $TargetPid" -ErrorAction Stop
			if ($null -eq $process) { throw "PID $TargetPid does not exist." }
			if ($process.Name -notmatch '^javaw?\.exe$') { throw "PID $TargetPid is '$($process.Name)', not a JVM." }
			$manifest.targetPid = $TargetPid
			$manifest.command = $process.CommandLine
			Write-Host "target: pid=$TargetPid name=$($process.Name) started=$($process.CreationDate)"
			Write-Host "command: $($process.CommandLine)"
			$manifest.target = [ordered]@{
				pid          = $TargetPid
				name         = $process.Name
				commandLine  = $process.CommandLine
				creationDate = "$($process.CreationDate)"
			}

			switch ($Artifact) {
				'jfr' {
					$recording = Join-Path $runDir "$Label.jfr"
					$name = "agent_${Label}_$stamp"
					# duration is a backstop: if the caller dies the JVM still flushes the file.
					& $jcmd $TargetPid JFR.start "name=$name" 'settings=profile' 'disk=true' `
						"duration=$($Seconds + 120)s" 'maxsize=256m' "filename=$recording" 'dumponexit=true' | Out-Null
					if ($LASTEXITCODE -ne 0) { throw "JFR.start failed on PID $TargetPid" }
					Write-Host "recording for $Seconds s; drive load against the application now"
					Start-Sleep -Seconds $Seconds
					& $jcmd $TargetPid JFR.stop "name=$name" "filename=$recording" | Out-Null
					if ($LASTEXITCODE -ne 0) { throw "JFR.stop failed on PID $TargetPid" }
					$manifest.artifactPath = $recording
				}
				'heap' {
					$dump = Join-Path $runDir "$Label.hprof"
					Write-Host 'GC.heap_dump pauses the JVM while the heap is written'
					& $jcmd $TargetPid GC.heap_dump $dump | Out-Null
					$exitCode = $LASTEXITCODE
					$manifest.artifactPath = $dump
				}
				'thread' {
					$dump = Join-Path $runDir "$Label-threads.json"
					& $jcmd $TargetPid Thread.dump_to_file -format=json $dump | Out-Null
					$exitCode = $LASTEXITCODE
					$manifest.artifactPath = $dump
				}
			}
		}
	}
}
catch {
	$manifest.error = $_.Exception.Message
	Write-Warning "capture failed: $($_.Exception.Message)"
}
finally {
	$stopwatch.Stop()
	$manifest.durationMs = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds)
	$manifest.exitCode = $exitCode

	$artifactPath = $manifest.artifactPath
	if ($artifactPath) {
		$verdict = switch ($Artifact) {
			'heap' {
				$header = if (Test-Path -LiteralPath $artifactPath) { Get-Content -LiteralPath $artifactPath -TotalCount 1 -Encoding ascii } else { '' }
				if ($header -like 'JAVA PROFILE*') { 'ok' } else { "bad header: $header" }
			}
			'thread' {
				if ((Test-Path -LiteralPath $artifactPath) -and (Get-Item -LiteralPath $artifactPath).Length -gt 0) { 'ok' } else { 'missing' }
			}
			default { Get-JfrVerdict $artifactPath }
		}
		$manifest.artifactVerdict = $verdict
		$manifest.valid = ($verdict -eq 'ok')
		if (Test-Path -LiteralPath $artifactPath) {
			$manifest.artifactMiB = [Math]::Round((Get-Item -LiteralPath $artifactPath).Length / 1MB, 2)
		}
	}

	$manifest.finishedUtc = (Get-Date).ToUniversalTime().ToString('o')
	$manifestPath = Join-Path $runDir "$Label-run.json"
	$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding utf8
	Write-Host "manifest: $manifestPath"
}

if ($manifest.error) { throw "capture failed: $($manifest.error)" }
if (-not $manifest.valid) { throw "artifact is not usable: $($manifest.artifactVerdict)" }
Write-Host "artifact: $($manifest.artifactPath) ($($manifest.artifactMiB) MiB)"
exit $exitCode
