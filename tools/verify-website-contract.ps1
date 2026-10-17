param(
    [string]$WebsiteDirectory = 'C:\Users\rybis\source\repos\MinePlayWebsite',
    [string]$FixtureDirectory = ''
)

$ErrorActionPreference = 'Stop'
$repositoryDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($FixtureDirectory)) {
    $FixtureDirectory = Join-Path $repositoryDirectory 'build\contract-fixtures'
}
$FixtureDirectory = [IO.Path]::GetFullPath($FixtureDirectory)
$WebsiteDirectory = [IO.Path]::GetFullPath($WebsiteDirectory)
$contractPath = Join-Path $WebsiteDirectory 'Api\SnapshotContract.cs'
$validatorPath = Join-Path $WebsiteDirectory 'Data\SnapshotValidator.cs'
foreach ($requiredPath in @($contractPath, $validatorPath)) {
    if (!(Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Receiver source file is missing: $requiredPath"
    }
}
if (!(Test-Path -LiteralPath $FixtureDirectory -PathType Container) -or
    @(Get-ChildItem -LiteralPath $FixtureDirectory -Filter '*.json' -File).Count -eq 0) {
    throw "No plugin-generated JSON fixtures in $FixtureDirectory. Run the plugin tests first."
}

# All generated files and build output stay in the plugin checkout. The website
# implementation is linked read-only; its project, settings and database are unused.
$harnessDirectory = Join-Path $repositoryDirectory 'build\website-contract-check'
[IO.Directory]::CreateDirectory($harnessDirectory) | Out-Null
$xmlContractPath = [Security.SecurityElement]::Escape($contractPath)
$xmlValidatorPath = [Security.SecurityElement]::Escape($validatorPath)
$project = @"
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
    <EnableDefaultCompileItems>false</EnableDefaultCompileItems>
  </PropertyGroup>
  <ItemGroup>
    <Compile Include="Program.cs" />
    <Compile Include="$xmlContractPath" Link="SnapshotContract.cs" />
    <Compile Include="$xmlValidatorPath" Link="SnapshotValidator.cs" />
  </ItemGroup>
</Project>
"@
$program = @'
using System.Text.Json;
using MinePlayWebsite.Data;

if (args.Length != 1) return 2;
var files = Directory.GetFiles(args[0], "*.json").Order(StringComparer.Ordinal).ToArray();
if (files.Length == 0) return 2;
var failures = 0;
var immutableMatches = new Dictionary<(Guid Source, Guid Session), string>();
foreach (var file in files)
{
    try
    {
        var bytes = File.ReadAllBytes(file);
        using var document = JsonDocument.Parse(bytes);
        var sourceId = Guid.Parse(document.RootElement.GetProperty("source_id").GetString()!);
        var eventId = document.RootElement.GetProperty("event_id").GetString();
        var validated = SnapshotValidator.Validate(bytes, sourceId, eventId);
        var matchKey = (sourceId, Guid.Parse(validated.Payload.Match.SessionId));
        if (immutableMatches.TryGetValue(matchKey, out var previousHash) && previousHash != validated.ImmutableHash)
            throw new SnapshotValidationException("immutable_match_conflict", 409);
        immutableMatches[matchKey] = validated.ImmutableHash;
        Console.WriteLine($"PASS {Path.GetFileName(file)}: revision {validated.Revision}, {validated.Payload.Match.Participants.Length} participants, {bytes.Length} bytes");
    }
    catch (SnapshotValidationException error)
    {
        failures++;
        Console.Error.WriteLine($"FAIL {Path.GetFileName(file)}: {error.Code}");
    }
    catch (Exception error)
    {
        failures++;
        Console.Error.WriteLine($"FAIL {Path.GetFileName(file)}: {error.GetType().Name}");
    }
}
Console.WriteLine($"Actual website contract validation: {files.Length - failures}/{files.Length} passed; immutable facts compared within each match. This does not test HTTP, authentication or PostgreSQL persistence.");
return failures == 0 ? 0 : 1;
'@
$utf8 = [Text.UTF8Encoding]::new($false)
$projectPath = Join-Path $harnessDirectory 'WebsiteContractCheck.csproj'
[IO.File]::WriteAllText($projectPath, $project, $utf8)
[IO.File]::WriteAllText((Join-Path $harnessDirectory 'Program.cs'), $program, $utf8)
# No package dependencies: framework references resolve from the installed SDK.
$nugetPath = Join-Path $harnessDirectory 'NuGet.Config'
[IO.File]::WriteAllText($nugetPath, '<configuration><packageSources><clear /></packageSources></configuration>', $utf8)

& dotnet restore $projectPath --configfile $nugetPath --verbosity quiet
if ($LASTEXITCODE -ne 0) { throw "Receiver contract harness restore failed (exit $LASTEXITCODE)." }
& dotnet run --project $projectPath --no-restore --configuration Release -- $FixtureDirectory
if ($LASTEXITCODE -ne 0) { throw "Receiver contract validation failed (exit $LASTEXITCODE)." }
