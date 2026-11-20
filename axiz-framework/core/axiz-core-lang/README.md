# axiz-core-lang

Pure Java language helpers: `Checks`, `Strings`, `Dates`, `ValueSnapshots` and `Result` (including `Ok` and `Err`).
This module depends on no other Axiz module and has no transport, JSON, persistence or runtime wiring.
JSpecify annotations express null contracts; the JDK provides the implementation.

Declare `pe.axiz:axiz-core-lang` directly when importing `pe.axiz.core.lang`.
`axiz-core-commons` retains request/page/header contracts, concurrency and transaction infrastructure.

`ValueSnapshots.map` detaches scalar/map/list value trees recursively into immutable collections,
preserving nulls. String keys, strings, booleans and immutable primitive/BigDecimal/BigInteger
numbers are supported; non-finite numbers, mutable leaves, non-string keys and nesting beyond
64 levels are rejected. It performs no parsing, serialization or I/O and imports no JSON library.
Generated models and event records reuse it for nested value maps; collections cannot retain
mutable nested state from their callers.
