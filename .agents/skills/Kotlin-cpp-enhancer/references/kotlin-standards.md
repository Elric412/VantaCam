# Kotlin Deep Standards Reference
# For: kotlin-cpp-engineer skill
# Read when: coroutines, Flow, Hilt, KMP, testing, or architecture details needed

---

## Table of Contents
1. [Coroutines — Structured Concurrency Deep Patterns](#1-coroutines)
2. [Flow — Production Stream Patterns](#2-flow)
3. [Sealed Classes — Complete State Modelling](#3-sealed-classes)
4. [Hilt Dependency Injection — Module Architecture](#4-hilt)
5. [Testing — Coroutine and Flow Testing](#5-testing)
6. [KMP — Kotlin Multiplatform Boundaries](#6-kmp)
7. [Performance — Hot Paths in Kotlin](#7-performance)

---

## 1. Coroutines — Structured Concurrency Deep Patterns

### Scope design — never create orphan coroutines

```kotlin
// WRONG: creates orphan coroutine outside structured hierarchy
class DataSyncService {
    fun sync() {
        GlobalScope.launch { doSync() }  // No lifecycle control. Leaks.
    }
}

// CORRECT: inject a scope, respect structured concurrency
class DataSyncService @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val syncRepository: SyncRepository,
) {
    fun sync(): Job = scope.launch(Dispatchers.IO) {
        syncRepository.performSync()
            .onFailure { error -> logger.e("Sync failed", error.cause) }
    }
}
```

### CoroutineExceptionHandler — last-resort handler, not primary error handling

```kotlin
// Use for logging and crash reporting — not for recovery
val handler = CoroutineExceptionHandler { coroutineContext, throwable ->
    // Only reached for uncaught exceptions in launch {} (not async {})
    logger.e("Unhandled coroutine exception in $coroutineContext", throwable)
    crashReporter.recordException(throwable)
}

// Install at scope level
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
```

### SupervisorJob — prevent cascade failures in parallel work

```kotlin
// With regular Job: one child failure cancels all siblings
// With SupervisorJob: each child is independent

class ParallelDataLoader @Inject constructor(
    private val userRepo: UserRepository,
    private val feedRepo: FeedRepository,
) {
    suspend fun loadDashboard(userId: UserId): DashboardData = coroutineScope {
        // supervisorScope so one failure doesn't cancel the other
        supervisorScope {
            val userDeferred = async { userRepo.getUser(userId) }
            val feedDeferred = async { feedRepo.getFeed(userId) }

            val user = userDeferred.await()
            val feed = try {
                feedDeferred.await()
            } catch (e: CancellationException) {
                throw e   // Always rethrow CancellationException
            } catch (e: Exception) {
                Result.Failure(DomainError.NetworkError("Feed unavailable", e))
            }

            DashboardData(user = user, feed = feed)
        }
    }
}
```

### Dispatcher discipline

```kotlin
// Rule: declare expected dispatcher in KDoc, but don't assume it inside suspend functions
// Callers can always call withContext if they need a different dispatcher

/**
 * Fetches user data from the network.
 * Safe to call from any dispatcher; internally switches to [Dispatchers.IO].
 */
suspend fun fetchUser(id: UserId): Result<User> = withContext(Dispatchers.IO) {
    runCatching { api.getUser(id.value) }
        .map { it.toDomain() }
        .toResult()  // extension to convert runCatching Result to domain Result
}

// Extension to bridge kotlin.Result → domain Result
fun <T> kotlin.Result<T>.toResult(): Result<T> = fold(
    onSuccess = { Result.Success(it) },
    onFailure = { Result.Failure(DomainError.NetworkError(it.message ?: "Unknown", it)) }
)
```

### Cancellation — always handle CancellationException correctly

```kotlin
// CancellationException MUST be rethrown — catching it breaks structured concurrency
suspend fun riskyOperation(): String {
    return try {
        withTimeout(5_000) { doNetworkCall() }
    } catch (e: CancellationException) {
        throw e                                // MUST rethrow
    } catch (e: TimeoutCancellationException) {
        "timeout"                              // TimeoutCancellationException is a CancellationException
                                               // so this branch is dead in current form
                                               // — check timeout BEFORE cancellation
    } catch (e: IOException) {
        "network error"
    }
}

// CORRECT handling of timeout vs cancellation:
suspend fun riskyOperation(): String = try {
    withTimeout(5_000) { doNetworkCall() }
} catch (e: TimeoutCancellationException) {
    "timeout"   // Specific subtype — safe to catch separately
}
// Let other CancellationException propagate
```

---

## 2. Flow — Production Stream Patterns

### StateFlow vs SharedFlow — choose deliberately

```kotlin
// StateFlow: for UI state (has initial value, replays latest to new collectors)
private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

// SharedFlow: for one-time events (no initial value, configurable replay)
private val _events = MutableSharedFlow<UiEvent>(
    extraBufferCapacity = 16,  // non-zero to prevent suspension on emit from non-suspend context
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
val events: SharedFlow<UiEvent> = _events.asSharedFlow()

// Emit events from ViewModel without suspend:
fun onLoginClick() {
    viewModelScope.launch {
        _events.emit(UiEvent.NavigateToHome)
    }
}
```

### Flow operators — production patterns

```kotlin
// Debounce search input
fun observeSearch(query: StateFlow<String>): Flow<SearchResult> =
    query
        .debounce(300)
        .filter { it.length >= 2 }
        .distinctUntilChanged()
        .flatMapLatest { searchQuery ->   // cancels previous search on new input
            repository.search(searchQuery)
                .catch { e ->
                    emit(SearchResult.Error(e.message ?: "Search failed"))
                }
        }
        .flowOn(Dispatchers.IO)

// Combine multiple streams
val dashboardFlow: Flow<DashboardState> = combine(
    userRepository.observeUser(userId),
    settingsRepository.observeSettings(),
    notificationsRepository.observeUnreadCount(),
) { user, settings, unreadCount ->
    DashboardState(user = user, settings = settings, unreadCount = unreadCount)
}.distinctUntilChanged()
```

### Collecting Flow safely in Android (lifecycle-aware)

```kotlin
// Fragment/Activity — never use lifecycleScope.launch + collect (lifecycle unsafe)
// CORRECT: repeatOnLifecycle
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state ->
            renderState(state)
        }
    }
}

// For Compose:
@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // ...
}
```

---

## 3. Sealed Classes — Complete State Modelling

### UI state machine — exhaustive by design

```kotlin
sealed interface OrderUiState {
    data object Loading : OrderUiState
    data class Loaded(val order: Order, val actions: List<OrderAction>) : OrderUiState
    data class Error(val error: DomainError, val retryable: Boolean) : OrderUiState
    data object Completed : OrderUiState
}

// Render — compiler enforces exhaustiveness (no else branch)
fun render(state: OrderUiState) = when (state) {
    is OrderUiState.Loading   -> showLoading()
    is OrderUiState.Loaded    -> showOrder(state.order, state.actions)
    is OrderUiState.Error     -> showError(state.error, state.retryable)
    is OrderUiState.Completed -> showCompletion()
}
```

### Domain events — typed and traceable

```kotlin
sealed class OrderEvent {
    data class Placed(val orderId: OrderId, val timestamp: Instant) : OrderEvent()
    data class PaymentConfirmed(val orderId: OrderId, val transactionId: String) : OrderEvent()
    data class Shipped(val orderId: OrderId, val trackingNumber: String) : OrderEvent()
    data class Delivered(val orderId: OrderId, val deliveredAt: Instant) : OrderEvent()
    data class Cancelled(val orderId: OrderId, val reason: CancellationReason) : OrderEvent()
}
```

---

## 4. Hilt Dependency Injection — Module Architecture

### Module design rules

```kotlin
// Rule 1: bind interfaces, not concrete types
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Binds
    @Singleton
    abstract fun bindOrderRepository(impl: OrderRepositoryImpl): OrderRepository
}

// Rule 2: separate @Provides for external types (Retrofit, Room, etc.)
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(
        @AuthInterceptor authInterceptor: Interceptor,
        @LoggingInterceptor loggingInterceptor: Interceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, @BaseUrl baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
}

// Rule 3: custom scopes for feature modules
@Scope
@Retention(AnnotationRetention.RUNTIME)
annotation class FeatureScope

@Module
@InstallIn(ActivityComponent::class)
abstract class CheckoutModule {
    @Binds
    @ActivityScoped
    abstract fun bindCheckoutNavigator(impl: CheckoutNavigatorImpl): CheckoutNavigator
}
```

---

## 5. Testing — Coroutine and Flow Testing

```kotlin
class UserViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()   // swaps Main dispatcher with TestDispatcher

    private val userRepository = mockk<UserRepository>()
    private lateinit var viewModel: UserViewModel

    @BeforeEach
    fun setup() {
        viewModel = UserViewModel(
            getUser = GetUserUseCase(userRepository),
            savedStateHandle = SavedStateHandle(mapOf(KEY_USER_ID to "user-123")),
        )
    }

    @Test
    fun `loads user successfully on init`() = runTest {
        val expected = User(id = UserId("user-123"), name = "Alice")
        coEvery { userRepository.getUser(UserId("user-123")) } returns Result.Success(expected)

        val states = mutableListOf<UserUiState>()
        val job = launch(UnconfinedTestDispatcher()) {
            viewModel.state.toList(states)
        }

        advanceUntilIdle()

        assertThat(states.last()).isEqualTo(UserUiState.Loaded(expected))
        job.cancel()
    }

    @Test
    fun `shows error when repository fails`() = runTest {
        coEvery { userRepository.getUser(any()) } returns
            Result.Failure(DomainError.NetworkError("No connection"))

        advanceUntilIdle()

        assertThat(viewModel.state.value).isInstanceOf<UserUiState.Error>()
    }
}

// MainDispatcherRule — standard test infrastructure
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) =
        Dispatchers.setMain(testDispatcher)
    override fun finished(description: Description) =
        Dispatchers.resetMain()
}
```

---

## 6. KMP — Kotlin Multiplatform Boundaries

```kotlin
// Shared module: pure business logic, no platform APIs
// commonMain:
expect class PlatformLogger {
    fun log(level: LogLevel, tag: String, message: String)
}

class OrderService(
    private val repository: OrderRepository,
    private val logger: PlatformLogger,
) {
    suspend fun placeOrder(request: PlaceOrderRequest): Result<Order> {
        logger.log(LogLevel.INFO, TAG, "Placing order: ${request.productId}")
        return repository.createOrder(request)
            .onFailure { logger.log(LogLevel.ERROR, TAG, "Order failed: $it") }
    }
}

// androidMain:
actual class PlatformLogger actual constructor() {
    actual fun log(level: LogLevel, tag: String, message: String) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO  -> Log.i(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
    }
}

// iosMain:
actual class PlatformLogger actual constructor() {
    actual fun log(level: LogLevel, tag: String, message: String) {
        NSLog("[$level][$tag] $message")
    }
}
```

---

## 7. Performance — Hot Paths in Kotlin

```kotlin
// Avoid allocations in hot paths (reuse objects, use primitive arrays)
// Use sequences for lazy evaluation of large collections
val expensiveResult = hugeList
    .asSequence()                          // lazy — no intermediate list allocation
    .filter { it.isActive }
    .map { it.toDomain() }
    .take(100)
    .toList()                              // only here does the list get built

// Inline functions: zero overhead for HOF in hot paths
inline fun <T> List<T>.forEachFast(action: (T) -> Unit) {
    val n = size
    for (i in 0 until n) {
        action(get(i))                     // no iterator allocation
    }
}

// Value classes: zero-overhead domain wrappers
@JvmInline
value class UserId(val value: String)

@JvmInline
value class OrderId(val value: Long)

// These compile to raw String/Long — zero boxing, zero allocation overhead
```
