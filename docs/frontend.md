# Frontend (Jetpack Compose) Architecture

## 1. Architectural Pattern
- **Pattern:** MVVM / MVI (Unidirectional Data Flow - UDF)
- **UI Toolkit:** Jetpack Compose with Material 3
- **State Handling:** `StateFlow<UIState>` collected via `collectAsStateWithLifecycle()` in composables.
- **Side Effects:** Single-event channels or `LaunchedEffect` / `rememberUpdatedState` for snackbars, navigation events.

## 2. Directory Structure
```
app/src/main/java/com/example/kotlinapp/
├── data/
│   ├── local/          # Room DB, DataStore
│   ├── remote/         # Retrofit / Ktor client, DTOs
│   └── repository/     # Repository implementations
├── domain/
│   ├── model/          # Business domain entities
│   └── usecase/        # UseCases / Interactors
├── ui/
│   ├── theme/          # Color, Type, Theme, Shape definitions
│   ├── components/     # Reusable custom composables
│   └── screens/        # Feature screens and ViewModels
└── MainActivity.kt     # App entry point & Navigation Host
```

## 3. Best Practices
- Keep composables stateless where possible via state hoisting.
- Use `@Preview` annotations with Light and Dark mode variations.
- Optimize recomposition with stable data classes and immutable collections.
