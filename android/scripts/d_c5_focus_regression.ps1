<#
.SYNOPSIS
Runs bounded D-C5 presentation regression, without full UI/manual acceptance.
.EXAMPLE
.\android\scripts\d_c5_focus_regression.ps1 -Slice Create
#>
[CmdletBinding()]
param(
    [ValidateSet("Create", "Editor", "All")]
    [string]$Slice = "All",
    [string]$DeviceSerial = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$androidRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $SkipBuild) {
    Push-Location $androidRoot
    try {
        $jvmTests = @(
            "com.unarchive.android.ui.create.CreatePresentationTest",
            "com.unarchive.android.ui.state.NotesLibraryStateTest",
            "com.unarchive.android.ui.theme.UiPoliciesTest"
        )
        if ($Slice -ne "Create") {
            $jvmTests += @(
                "com.unarchive.android.editor.MarkdownEditorViewModelTest",
                "com.unarchive.android.editor.MarkdownEditorMigrationViewModelTest",
                "com.unarchive.android.editor.MarkdownAiProposalTest",
                "com.unarchive.android.editor.NoteEditorViewModelTest"
            )
        }
        $gradleArguments = @(':app:testDebugUnitTest')
        foreach ($test in $jvmTests) { $gradleArguments += @('--tests', $test) }
        $gradleArguments += @(':app:assembleDebug', ':app:assembleDebugAndroidTest', '--no-daemon')
        & .\gradlew.bat @gradleArguments
        if ($LASTEXITCODE -ne 0) { throw "D-C5 focused build/JVM regression failed ($LASTEXITCODE)." }
    } finally { Pop-Location }
}

$createTests = @(
    "com.unarchive.android.CreationFlowUiTest",
    "com.unarchive.android.D1NavigationTest",
    "com.unarchive.android.NotesLibraryUiTest",
    "com.unarchive.android.MotionStateUiTest"
)
$editorTests = @(
    "com.unarchive.android.CreationEditorFlowUiTest",
    "com.unarchive.android.editor.MarkdownEditorUiTest",
    "com.unarchive.android.NoteEditorUiTest",
    "com.unarchive.android.MotionNavigationUiTest"
)
$testClasses = switch ($Slice) {
    "Create" { $createTests }
    "Editor" { $editorTests }
    "All" { @($createTests + $editorTests | Select-Object -Unique) }
}

& (Join-Path $PSScriptRoot "d43_focus_acceptance.ps1") `
    -DeviceSerial $DeviceSerial -SkipBuild -ReportGroup "d-c5-$($Slice.ToLowerInvariant())" `
    -TestClass $testClasses `
    -SuccessMessage "D-C5 $Slice focused regression passed. Full UI/manual/business acceptance remains deferred."
