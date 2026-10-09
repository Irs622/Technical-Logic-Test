# Coding Standards & Guidelines

## 1. Kotlin Code Style
- Follow official Kotlin coding conventions and Google Java/Kotlin style guide.
- PascalCase for Classes, Interfaces, and Composable functions (e.g. `HomeScreen()`, `UserRepository`).
- camelCase for functions, variables, and properties (e.g. `getUserProfile()`, `isLoading`).
- UPPER_SNAKE_CASE for compile-time constants (e.g. `MAX_RETRY_COUNT`).

## 2. Compose Best Practices
- Every Composable should take a `modifier: Modifier = Modifier` as the first optional parameter.
- Hoist state to ensure composables remain testable and reusable.
- Avoid passing ViewModels down deep composable hierarchies; pass state and lambda event handlers instead.

## 3. Architecture Rules
- Keep UI layer decoupled from data sources.
- Domain layer (UseCases, Models) must be pure Kotlin without Android framework dependencies where possible.
- Never hardcode strings, dimensions, or colors; reference resources or design tokens.
