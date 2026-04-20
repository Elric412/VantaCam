# C++ Deep Standards Reference
# For: kotlin-cpp-engineer skill
# Read when: templates, concepts, threading, memory, build systems, or C++20/23 features needed

---

## Table of Contents
1. [C++20 Concepts — Type-Safe Templates](#1-concepts)
2. [RAII — Complete Resource Management Patterns](#2-raii)
3. [Error Handling — std::expected and Exception Safety](#3-error-handling)
4. [Threading — Correct Concurrent Code](#4-threading)
5. [Memory — Smart Pointers and Ownership Patterns](#5-memory)
6. [Ranges and Views — Modern Data Processing](#6-ranges)
7. [CMake — Production Build System](#7-cmake)
8. [Sanitizers and Static Analysis](#8-tools)

---

## 1. C++20 Concepts — Type-Safe Templates

```cpp
#include <concepts>

// Define concepts that precisely describe your requirements
template<typename T>
concept Serialisable = requires(T t, std::ostream& os) {
    { t.serialise(os) } -> std::same_as<void>;
    { T::deserialise(std::declval<std::istream&>()) } -> std::same_as<T>;
};

template<typename T>
concept Numeric = std::is_arithmetic_v<T> && !std::same_as<T, bool>;

template<typename T>
concept Container = requires(T t) {
    typename T::value_type;
    typename T::iterator;
    { t.begin() } -> std::same_as<typename T::iterator>;
    { t.end() }   -> std::same_as<typename T::iterator>;
    { t.size() }  -> std::convertible_to<std::size_t>;
};

// Use concepts in function signatures — much cleaner than SFINAE
template<Serialisable T>
void writeToFile(const std::filesystem::path& path, const T& object) {
    std::ofstream file(path, std::ios::binary);
    if (!file) throw std::runtime_error("Cannot open file: " + path.string());
    object.serialise(file);
}

// Constrained auto
auto processValue(Numeric auto value) {
    return value * value;
}

// Abbreviated function template with multiple constraints
template<Container C>
    requires Numeric<typename C::value_type>
auto sum(const C& container) {
    return std::reduce(container.begin(), container.end(),
                       typename C::value_type{0});
}
```

---

## 2. RAII — Complete Resource Management Patterns

### Universal RAII patterns

```cpp
// File RAII — prefer std::ifstream, but useful for C FILE* interop
class FileHandle {
public:
    explicit FileHandle(const char* path, const char* mode)
        : handle_(std::fopen(path, mode)) {
        if (!handle_) {
            throw std::system_error(errno, std::generic_category(),
                                    std::string("Failed to open: ") + path);
        }
    }

    ~FileHandle() noexcept {
        if (handle_) std::fclose(handle_);
    }

    FileHandle(const FileHandle&)            = delete;
    FileHandle& operator=(const FileHandle&) = delete;

    FileHandle(FileHandle&& other) noexcept
        : handle_(std::exchange(other.handle_, nullptr)) {}

    FileHandle& operator=(FileHandle&& other) noexcept {
        if (this != &other) {
            if (handle_) std::fclose(handle_);
            handle_ = std::exchange(other.handle_, nullptr);
        }
        return *this;
    }

    [[nodiscard]] FILE* get() const noexcept { return handle_; }
    [[nodiscard]] bool  valid() const noexcept { return handle_ != nullptr; }

private:
    FILE* handle_;
};

// Mutex RAII — always use std::lock_guard or std::unique_lock
class ThreadSafeQueue {
public:
    void push(int value) {
        std::lock_guard lock(mutex_);  // CTAD — no need to write std::lock_guard<std::mutex>
        queue_.push(value);
        cv_.notify_one();
    }

    [[nodiscard]] std::optional<int> tryPop() {
        std::unique_lock lock(mutex_);
        if (queue_.empty()) return std::nullopt;
        auto value = queue_.front();
        queue_.pop();
        return value;
    }

    [[nodiscard]] int waitAndPop() {
        std::unique_lock lock(mutex_);
        cv_.wait(lock, [this] { return !queue_.empty(); });
        auto value = queue_.front();
        queue_.pop();
        return value;
    }

private:
    std::queue<int>         queue_;
    mutable std::mutex      mutex_;
    std::condition_variable cv_;
};
```

### PIMPL idiom — stable ABI and reduced compilation

```cpp
// header: engine.hpp
#pragma once
#include <memory>

class Engine {
public:
    Engine();
    ~Engine();                                  // must be defined where Impl is complete

    Engine(Engine&&) noexcept;
    Engine& operator=(Engine&&) noexcept;

    Engine(const Engine&)            = delete;
    Engine& operator=(const Engine&) = delete;

    void start();
    void stop();
    [[nodiscard]] bool isRunning() const noexcept;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// source: engine.cpp
struct Engine::Impl {
    std::atomic<bool>     running{false};
    std::thread           workerThread;
    std::vector<Pipeline> pipelines;
    // ...
};

Engine::Engine()  : impl_(std::make_unique<Impl>()) {}
Engine::~Engine() = default;  // defined here where Impl is complete

Engine::Engine(Engine&&) noexcept            = default;
Engine& Engine::operator=(Engine&&) noexcept = default;
```

---

## 3. Error Handling — std::expected and Exception Safety Levels

### Exception safety levels — document which you provide

```cpp
// Nothrow guarantee: function never throws, marked noexcept
[[nodiscard]] std::size_t containerSize() const noexcept {
    return data_.size();
}

// Strong guarantee: if this throws, state is as if the call never happened
// (achieved via copy-and-swap or transactional update)
void updateRecord(const Record& record) {
    auto newData = data_;            // copy
    newData.update(record);          // mutate copy
    data_ = std::move(newData);      // nothrow swap: commits the change
}

// Basic guarantee: state is valid but may be changed (no leaks, no corruption)
// Most STL operations provide this
```

### std::expected for recoverable errors (C++23 / tl::expected for C++17)

```cpp
#include <expected>     // C++23
// Or: #include <tl/expected.hpp>   for C++17

enum class ParseError { InvalidFormat, OutOfRange, EmptyInput };

[[nodiscard]] std::expected<int, ParseError>
parsePositiveInt(std::string_view input) noexcept {
    if (input.empty()) return std::unexpected(ParseError::EmptyInput);

    int result = 0;
    auto [ptr, ec] = std::from_chars(input.data(), input.data() + input.size(), result);

    if (ec == std::errc::result_out_of_range) return std::unexpected(ParseError::OutOfRange);
    if (ec != std::errc{} || ptr != input.data() + input.size())
        return std::unexpected(ParseError::InvalidFormat);
    if (result <= 0) return std::unexpected(ParseError::OutOfRange);

    return result;
}

// Chaining with and_then / transform
auto result = parsePositiveInt(userInput)
    .and_then([](int n) -> std::expected<double, ParseError> {
        return std::sqrt(static_cast<double>(n));
    })
    .transform([](double v) { return std::format("{:.2f}", v); });

if (result) {
    std::println("Result: {}", *result);
} else {
    switch (result.error()) {
        case ParseError::EmptyInput:    std::println("Empty input"); break;
        case ParseError::OutOfRange:    std::println("Out of range"); break;
        case ParseError::InvalidFormat: std::println("Invalid format"); break;
    }
}
```

---

## 4. Threading — Correct Concurrent Code

### Thread-safe singleton with std::call_once

```cpp
class Registry {
public:
    [[nodiscard]] static Registry& instance() {
        std::call_once(initFlag_, [] {
            instance_.reset(new Registry());
        });
        return *instance_;
    }

    // All public methods are thread-safe via their own locking
    void registerHandler(std::string_view name, Handler handler) {
        std::unique_lock lock(mutex_);
        handlers_.emplace(name, std::move(handler));
    }

private:
    Registry() = default;

    static std::once_flag               initFlag_;
    static std::unique_ptr<Registry>    instance_;

    mutable std::shared_mutex           mutex_;
    std::unordered_map<std::string, Handler> handlers_;
};
```

### std::shared_mutex for read-heavy workloads

```cpp
class Cache {
public:
    [[nodiscard]] std::optional<Value> get(const Key& key) const {
        std::shared_lock lock(mutex_);              // multiple readers simultaneously
        auto it = map_.find(key);
        return it != map_.end() ? std::make_optional(it->second) : std::nullopt;
    }

    void insert(const Key& key, Value value) {
        std::unique_lock lock(mutex_);              // exclusive writer
        map_.emplace(key, std::move(value));
    }

    void invalidate(const Key& key) {
        std::unique_lock lock(mutex_);
        map_.erase(key);
    }

private:
    mutable std::shared_mutex                     mutex_;
    std::unordered_map<Key, Value>                map_;
};
```

### Lock-free with std::atomic for simple counters and flags

```cpp
class RequestCounter {
public:
    void increment() noexcept {
        count_.fetch_add(1, std::memory_order_relaxed);
    }

    void decrement() noexcept {
        count_.fetch_sub(1, std::memory_order_relaxed);
    }

    [[nodiscard]] int64_t value() const noexcept {
        return count_.load(std::memory_order_relaxed);
    }

    // Compare-and-swap for conditional decrement (atomic read-modify-write)
    bool tryAcquire(int64_t required) noexcept {
        int64_t current = count_.load(std::memory_order_acquire);
        while (current >= required) {
            if (count_.compare_exchange_weak(current, current - required,
                    std::memory_order_acq_rel, std::memory_order_acquire)) {
                return true;
            }
            // current is updated by compare_exchange_weak on failure — retry
        }
        return false;
    }

private:
    std::atomic<int64_t> count_{0};
};
```

### Thread pool with std::jthread (C++20)

```cpp
class ThreadPool {
public:
    explicit ThreadPool(std::size_t threadCount = std::thread::hardware_concurrency()) {
        workers_.reserve(threadCount);
        for (std::size_t i = 0; i < threadCount; ++i) {
            workers_.emplace_back([this](std::stop_token stopToken) {
                workerLoop(stopToken);
            });
        }
    }

    // std::jthread stops and joins automatically in its destructor

    template<typename F, typename... Args>
    [[nodiscard]] auto submit(F&& f, Args&&... args)
        -> std::future<std::invoke_result_t<F, Args...>>
    {
        using ReturnType = std::invoke_result_t<F, Args...>;
        auto task = std::make_shared<std::packaged_task<ReturnType()>>(
            std::bind(std::forward<F>(f), std::forward<Args>(args)...)
        );
        auto future = task->get_future();

        {
            std::unique_lock lock(mutex_);
            if (stopping_) throw std::runtime_error("ThreadPool is shutting down");
            tasks_.emplace([task]() { (*task)(); });
        }
        cv_.notify_one();
        return future;
    }

private:
    void workerLoop(std::stop_token stopToken) {
        while (true) {
            std::unique_lock lock(mutex_);
            cv_.wait(lock, [this, &stopToken] {
                return !tasks_.empty() || stopToken.stop_requested();
            });
            if (stopToken.stop_requested() && tasks_.empty()) return;
            auto task = std::move(tasks_.front());
            tasks_.pop();
            lock.unlock();
            task();
        }
    }

    std::vector<std::jthread>        workers_;
    std::queue<std::function<void()>> tasks_;
    mutable std::mutex               mutex_;
    std::condition_variable          cv_;
    bool                             stopping_{false};
};
```

---

## 5. Memory — Smart Pointers and Ownership Patterns

### Ownership rules — apply consistently

```cpp
// unique_ptr: sole ownership — most common for heap objects
std::unique_ptr<Shader> loadShader(const std::filesystem::path& path) {
    // Factory function returns unique ownership
    return std::make_unique<Shader>(path);
}

// shared_ptr: shared ownership — use sparingly (reference counting overhead)
class Scene {
    std::vector<std::shared_ptr<Mesh>> meshes_;  // scene shares ownership of meshes
};

class RenderPass {
    std::shared_ptr<Mesh> mesh_;                  // render pass also holds a reference
};

// weak_ptr: non-owning observer — breaks shared_ptr cycles
class Node {
    std::vector<std::shared_ptr<Node>> children_;  // owns children
    std::weak_ptr<Node>               parent_;     // observes parent without owning it
};

// Non-owning raw pointer/reference: observer that doesn't need lifetime control
class Renderer {
public:
    explicit Renderer(const Config& config) : config_(config) {}   // reference = non-owning observer
    void render(const Scene* scene) { /* scene outlives this call */ }
private:
    const Config& config_;   // reference = assumed to outlive Renderer
};
```

### Custom deleter for C library resources

```cpp
// Typed deleter for OpenSSL, SQLite, or any C API
struct BIODeleter {
    void operator()(BIO* bio) const noexcept { BIO_free_all(bio); }
};
using BIOPtr = std::unique_ptr<BIO, BIODeleter>;

struct SqliteDeleter {
    void operator()(sqlite3* db) const noexcept { sqlite3_close(db); }
};
using SqlitePtr = std::unique_ptr<sqlite3, SqliteDeleter>;

// Usage — guaranteed cleanup even on exception
SqlitePtr openDatabase(const std::string& path) {
    sqlite3* raw = nullptr;
    if (sqlite3_open(path.c_str(), &raw) != SQLITE_OK) {
        throw std::runtime_error("Failed to open database: " + path);
    }
    return SqlitePtr(raw);  // transfers ownership to unique_ptr
}
```

---

## 6. Ranges and Views — Modern Data Processing

```cpp
#include <ranges>
#include <algorithm>

struct Employee {
    std::string name;
    int         salary;
    Department  dept;
};

// Compose views lazily — no intermediate container allocation
auto getTopEarnerNames(const std::vector<Employee>& employees,
                        Department                   dept,
                        int                          minSalary) {
    return employees
        | std::views::filter([&](const Employee& e) { return e.dept == dept; })
        | std::views::filter([&](const Employee& e) { return e.salary >= minSalary; })
        | std::views::transform(&Employee::name)  // pointer to member as projection
        | std::views::take(10);
    // Returns a lazy view — no copy, no allocation until iterated
}

// Collect to vector only when needed
auto names = getTopEarnerNames(employees, Department::Engineering, 100'000)
             | std::ranges::to<std::vector>();   // C++23

// Sort with projection (no lambda needed for simple field access)
std::ranges::sort(employees, std::less{}, &Employee::salary);

// Group by with ranges (C++23 chunk_by)
auto byDepartment = employees
    | std::views::chunk_by([](const Employee& a, const Employee& b) {
          return a.dept == b.dept;
      });
```

---

## 7. CMake — Production Build System

```cmake
# CMakeLists.txt — production structure
cmake_minimum_required(VERSION 3.25)
project(MyProject VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)   # no compiler-specific extensions
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)  # for clang-tidy

# Warnings as errors in CI — configure per target, not globally
add_library(project_warnings INTERFACE)
target_compile_options(project_warnings INTERFACE
    $<$<CXX_COMPILER_ID:MSVC>:/W4 /WX>
    $<$<NOT:$<CXX_COMPILER_ID:MSVC>>:-Wall -Wextra -Wpedantic -Werror>
)

# Core library — specify all sources explicitly (no globbing)
add_library(core STATIC
    src/engine.cpp
    src/renderer.cpp
    src/shader.cpp
)
target_include_directories(core
    PUBLIC  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src
)
target_link_libraries(core
    PUBLIC  project_warnings
    PRIVATE fmt::fmt
            spdlog::spdlog
)
target_compile_features(core PUBLIC cxx_std_23)

# Sanitiser presets — enable in Debug builds via presets
if(ENABLE_ASAN)
    target_compile_options(core PRIVATE -fsanitize=address,undefined)
    target_link_options(core PRIVATE -fsanitize=address,undefined)
endif()

# Tests
enable_testing()
add_subdirectory(tests)
```

---

## 8. Sanitizers and Static Analysis

### Build presets for sanitiser-enabled builds

```json
// CMakePresets.json
{
    "version": 3,
    "configurePresets": [
        {
            "name": "debug-asan",
            "displayName": "Debug + AddressSanitizer + UBSan",
            "generator": "Ninja",
            "cacheVariables": {
                "CMAKE_BUILD_TYPE": "Debug",
                "ENABLE_ASAN": "ON",
                "CMAKE_CXX_FLAGS": "-fno-omit-frame-pointer"
            }
        },
        {
            "name": "debug-tsan",
            "displayName": "Debug + ThreadSanitizer",
            "cacheVariables": {
                "CMAKE_BUILD_TYPE": "Debug",
                "CMAKE_CXX_FLAGS": "-fsanitize=thread"
            }
        }
    ]
}
```

### clang-tidy configuration

```yaml
# .clang-tidy
Checks: >
  clang-diagnostic-*,
  clang-analyzer-*,
  cppcoreguidelines-*,
  modernize-*,
  performance-*,
  readability-*,
  -modernize-use-trailing-return-type,
  -readability-named-parameter,
  -cppcoreguidelines-avoid-magic-numbers
WarningsAsErrors: "*"
HeaderFilterRegex: "include/.*\\.hpp"
FormatStyle: file
```

### Memory-safe code practices checklist

```cpp
// Always check: valgrind, ASan, UBSan, clang-tidy before shipping

// UBSan catches: signed overflow, out-of-bounds, misaligned access, null deref
// Compile with: -fsanitize=address,undefined -fno-sanitize-recover=all

// Thread sanitiser catches: data races, lock order violations
// Compile with: -fsanitize=thread (incompatible with ASan — separate build)

// Useful compile flags for maximum warning coverage:
// -Wall -Wextra -Wpedantic -Wconversion -Wsign-conversion -Wnull-dereference
// -Wdouble-promotion -Wformat=2 -Wimplicit-fallthrough
```
