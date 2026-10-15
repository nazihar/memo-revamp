# iOS / iPadOS app (placeholder)

SwiftUI app with PDFKit and PencilKit, consuming the shared Kotlin Multiplatform framework from `/shared` (Obj-C export + SKIE; see `AGENTS.md §2`).

Phase 0 decision (Q4): only the `/shared` KMP module exists, with a JVM target and `commonTest`. The Xcode project, the iOS targets in `/shared` and the macOS CI runners arrive in **Phase 6a** (mobile approver app).
