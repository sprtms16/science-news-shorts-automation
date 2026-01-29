# Science News Shorts Automation Style Guide

This style guide outlines the coding conventions and best practices for the Science News Shorts Automation project.

## General Principles
- **Readability**: Code should be easy to read and understand.
- **Consistency**: Maintain a consistent style across backend (Kotlin) and frontend (React).
- **Safety**: Ensure all external API calls (Gemini, YouTube, Pexels) are handled with robust error checking and rate limiting.

## Backend (Kotlin / Spring Boot)
- **Naming**: Use `PascalCase` for classes and `camelCase` for variables/functions.
- **Named Arguments**: Always use named arguments when calling constructors or `copy()` methods for data classes, especially `VideoHistory`.
- **DateTime**: Use `java.time.LocalDateTime` for timestamps. Ensure `updatedAt` is updated on every mutation.
- **Kafka**: Use descriptive event names and ensure all published events are documented in `Events.kt`.
- **FFmpeg**: When executing shell commands, robustly escape paths that might contain special characters (especially single quotes).

## Frontend (React / TypeScript)
- **Functional Components**: Use functional components with hooks.
- **TypeScript**: Strictly use types; avoid `any`.
- **Theme**: Use the defined CSS variables in `index.css` for consistent glassmorphism and theme support.
- **i18n**: All user-facing text must be externalized to `i18n.ts`.

## Code Review Focus
- **Correctness**: Check for logical errors in the video production pipeline.
- **API Quota Management**: Look for potential waste of Gemini or YouTube API tokens.
- **Security**: Prevent hardcoding of API keys; use environment variables.
