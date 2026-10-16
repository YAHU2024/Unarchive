# D-C1 compile and dependency-resolution fixture

This module is intentionally isolated from `:app`. It resolves the candidate
artifacts without exposing their types to production code. The first API compile
attempt is preserved in the stage report because current releases are not
compatible with the app's Kotlin 2.0.21/Compose 1.7.5 baseline.
