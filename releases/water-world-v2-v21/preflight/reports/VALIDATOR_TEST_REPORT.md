# Validator Regression Test Report

Run date: 2026-11-25

Command: `python -B -m unittest discover -s tools/water-world-v2-1/tests -p 'test_*.py' -v`

Result: **21 passed, 0 failed**.

Coverage includes successful/missing/corrupt PNGs, SHA and ICC mismatch, absent master analysis, missing overlay/heatmap/final ZIP, missing graph node, dangling edge, dependency cycle, filled unknown motion value, Core input/output consistency, and the BLOCKED/FAIL exit codes.

The real package Preflight returned `PREFLIGHT_PASS` / exit 0. The real full closure returned `WORK1_5_BLOCKED_MASTER_SOURCE` / exit 10 because the original master PNG bytes are not accessible to this execution workspace.

The pushed PR #3 tree contains the exact tested Validator and test files; all 71 remote package blob IDs match local files. GitHub Actions and commit status checks returned no run/status for the tooling/report commit, so hosted CI is marked NOT_RUN.
