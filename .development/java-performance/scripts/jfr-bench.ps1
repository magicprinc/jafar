#Requires -Version 5.1
<#
.SYNOPSIS
Compares candidate implementations of a snippet with fresh JVMs, warmup and JFR.

.DESCRIPTION
Answers "which of these two/three code variants is actually faster?" without touching the
project under test. Nothing is written into a repository: sources are copied into a run
directory, compiled there with javac, and every measured variant gets its own JVM so JIT
profiles never mix.

The input file must be a default-package class `Variants` (any file name, class name matters)
containing one `public static long <name>()` method per candidate and optionally
`public static void setup()` for shared input. See references/bench-contract.md.

Each measured block reports wall time per operation and bytes allocated by the measuring
thread. The script runs every variant in every round (order reversed on odd rounds), takes the
median per round, and refuses to declare a winner when the observed ranges overlap.

.PARAMETER VariantsPath
Path to the .java file declaring `Variants`.

.PARAMETER Names
Variant method names. Omit to take every `public static long ...()` from the file.

.PARAMETER Runs
Number of rounds (fresh JVMs per variant per round). Default 3.

.PARAMETER Jfr
Also record a JFR per variant per round, to explain *why* one is slower.

.EXAMPLE
.\jfr-bench.ps1 -VariantsPath .\bench\Variants.java -Jfr
#>
[CmdletBinding()]
param(
	[Parameter(Mandatory = $true)][string]$VariantsPath,
	[string[]]$Names,
	[ValidateRange(1, 20)][int]$Runs = 3,
	[ValidateRange(0, 30000)][int]$WarmupMs = 1500,
	[ValidateRange(20, 5000)][int]$BlockMs = 150,
	[ValidateRange(3, 50)][int]$Blocks = 7,
	[switch]$Jfr,
	[string]$OutDir = (Join-Path $env:LOCALAPPDATA 'jfr-perf'),
	[ValidatePattern('^[A-Za-z0-9_.-]+$')][string]$Label = 'bench',
	[switch]$KeepSources
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Split-Path (Split-Path (Get-Command 'java.exe' -ErrorAction Stop).Source) }
$java = Join-Path $javaHome 'bin\java.exe'
$javac = Join-Path $javaHome 'bin\javac.exe'
foreach ($tool in @($java, $javac)) {
	if (-not (Test-Path -LiteralPath $tool)) { throw "JDK tool missing: $tool (set JAVA_HOME to a JDK 21+)" }
}
$harness = Join-Path $PSScriptRoot 'BenchMain.java'
if (-not (Test-Path -LiteralPath $harness)) { throw "Harness source missing: $harness" }

$source = (Resolve-Path -LiteralPath $VariantsPath -ErrorAction Stop).Path
$variantText = Get-Content -LiteralPath $source -Raw
# BenchMain resolves methods with MethodHandles.publicLookup(), which cannot see a package-private class.
if ($variantText -notmatch '\bpublic\s+(?:final\s+)?class\s+Variants\b') {
	throw "$source must declare a default-package 'public class Variants' (package-private is invisible to the harness)."
}

if (-not $Names) {
	$found = [regex]::Matches($variantText, 'public\s+static\s+long\s+([A-Za-z_$][\w$]*)\s*\(')
	$Names = @($found | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne 'main' } | Select-Object -Unique)
}
if ($Names.Count -lt 2) {
	throw "Need at least two 'public static long name()' variants in $source (found: $($Names -join ', '))."
}

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$runDir = Join-Path ([IO.Path]::GetFullPath($OutDir)) "$Label-$stamp"
$classes = Join-Path $runDir 'classes'
[void][IO.Directory]::CreateDirectory($classes)

Copy-Item -LiteralPath $source -Destination (Join-Path $runDir 'Variants.java')
Copy-Item -LiteralPath $harness -Destination (Join-Path $runDir 'BenchMain.java')

$jdk = & $java '-XshowSettings:properties' '-version' 2>&1 | Where-Object { $_ -match 'java\.version\s*=' }
Write-Host "Compiling $($Names -join ', ') with $javac"
& $javac '-d' $classes (Join-Path $runDir 'Variants.java') (Join-Path $runDir 'BenchMain.java')
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

$observations = @()
$artifacts = @()

for ($round = 1; $round -le $Runs; $round++) {
	# Alternate the order so a drifting machine penalises every variant, not always the last one.
	$order = if ($round % 2 -eq 1) { @($Names) } else { @($Names[($Names.Count - 1)..0]) }
	foreach ($name in $order) {
		# No GC/JIT flags: every variant runs on the same default runtime configuration, and
		# pinning flags here would drift from what the application actually uses.
		$jvmArgs = @()
		if ($Jfr) {
			$recording = Join-Path $runDir "$name-r$round.jfr"
			$jvmArgs += "-XX:StartFlightRecording=filename=$recording,settings=profile,dumponexit=true,maxsize=256m"
			$artifacts += $recording
		}
		$jvmArgs += @('-cp', $classes, 'BenchMain', '--variant', $name,
			'--warmup-ms', $WarmupMs, '--block-ms', $BlockMs, '--blocks', $Blocks)

		Write-Host ("round {0}/{1}: {2}" -f $round, $Runs, $name)
		$output = & $java @jvmArgs 2>&1
		if ($LASTEXITCODE -ne 0) {
			throw "Measurement JVM failed for '$name' (exit $LASTEXITCODE):`n$($output -join "`n")"
		}
		foreach ($line in $output) {
			$text = "$line".Trim()
			if (-not $text.StartsWith('{')) { continue }
			$row = $text | ConvertFrom-Json
			$observations += [pscustomobject]@{
				variant    = $row.variant
				round      = $round
				block      = $row.block
				nsPerOp    = [double]$row.nsPerOp
				bytesPerOp = [double]$row.bytesPerOp
			}
		}
	}
}

if ($observations.Count -eq 0) { throw 'No measurements were produced.' }

function Get-MedianValue {
	param($Samples)
	$sorted = @($Samples | Sort-Object)
	$count = $sorted.Count
	if ($count -eq 0) { return [double]::NaN }
	if ($count % 2 -eq 1) { return [double]$sorted[[int](($count - 1) / 2)] }
	$upper = [int]($count / 2)
	return ([double]$sorted[$upper - 1] + [double]$sorted[$upper]) / 2.0
}

# variant -> round -> list of per-block ns/op
$perVariant = [ordered]@{}
foreach ($name in $Names) {
	$perVariant[$name] = @{}
}
foreach ($observation in $observations) {
	$rounds = $perVariant[$observation.variant]
	if ($null -eq $rounds) { continue }
	$key = [string]$observation.round
	if (-not $rounds.ContainsKey($key)) {
		$rounds[$key] = New-Object 'System.Collections.Generic.List[double]'
	}
	$rounds[$key].Add([double]$observation.nsPerOp)
}

$summary = @()
foreach ($name in $Names) {
	$roundMedians = New-Object 'System.Collections.Generic.List[double]'
	foreach ($key in ($perVariant[$name].Keys | Sort-Object)) {
		$roundMedians.Add((Get-MedianValue $perVariant[$name][$key]))
	}
	$bytes = New-Object 'System.Collections.Generic.List[double]'
	foreach ($observation in $observations) {
		if ($observation.variant -eq $name) { $bytes.Add([double]$observation.bytesPerOp) }
	}
	$summary += [pscustomobject]@{
		variant           = $name
		medianNsPerOp     = (Get-MedianValue $roundMedians)
		bestRoundNsPerOp  = ($roundMedians | Measure-Object -Minimum).Minimum
		worstRoundNsPerOp = ($roundMedians | Measure-Object -Maximum).Maximum
		medianBytesPerOp  = (Get-MedianValue $bytes)
	}
}

$best = ($summary | Sort-Object medianNsPerOp | Select-Object -First 1)
foreach ($row in $summary) {
	$row | Add-Member -NotePropertyName ratioVsBest -NotePropertyValue ($row.medianNsPerOp / $best.medianNsPerOp)
	$row | Add-Member -NotePropertyName verdict -NotePropertyValue ''
}
foreach ($row in $summary) {
	if ($row.variant -eq $best.variant) { $row.verdict = 'fastest'; continue }
	# Ranges must be disjoint for the difference to be believable at this sample size.
	$disjoint = $row.bestRoundNsPerOp -gt $best.worstRoundNsPerOp
	$row.verdict = if ($disjoint) { 'slower' } else { 'within-noise' }
}

$lines = @()
$lines += "# Bench: $Label"
$lines += ''
$lines += "- source: ``$source``"
$lines += "- java: $($jdk -join ' ')"
$lines += "- rounds: $Runs, warmup: ${WarmupMs}ms, block: ${BlockMs}ms x $Blocks"
$lines += "- run dir: ``$runDir``"
$lines += ''
$lines += '| variant | median ns/op | best | worst | bytes/op | vs fastest | verdict |'
$lines += '|---|---|---|---|---|---|---|'
foreach ($row in ($summary | Sort-Object medianNsPerOp)) {
	$lines += ("| {0} | {1:N1} | {2:N1} | {3:N1} | {4:N0} | {5:N2}x | {6} |" -f `
		$row.variant, $row.medianNsPerOp, $row.bestRoundNsPerOp, $row.worstRoundNsPerOp,
		$row.medianBytesPerOp, $row.ratioVsBest, $row.verdict)
}
$lines += ''
$lines += 'Differences inside overlapping best/worst ranges are reported as `within-noise`; do not'
$lines += 'report them as a speedup without more rounds or a quieter machine.'
$report = $lines -join [Environment]::NewLine

$reportPath = Join-Path $runDir 'bench.md'
Set-Content -LiteralPath $reportPath -Value $report -Encoding utf8
$summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runDir 'bench.json') -Encoding utf8
if (-not $KeepSources) {
	# Sources stay: they are the evidence of what was measured.
}

Write-Host ''
Write-Host $report
Write-Host ''
Write-Host "artifacts: $runDir"
if ($Jfr) { Write-Host "jfr: $($artifacts.Count) recording(s) in $runDir" }
