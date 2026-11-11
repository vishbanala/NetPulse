# NetPulse C monitoring engine + SQS worker
# MSYS2 UCRT64 (Windows) and Linux (Render/Docker).

CC       ?= gcc
CFLAGS   ?= -std=c11 -Wall -Wextra -O2
CPPFLAGS += -Iinclude -Isrc
LDFLAGS  ?=
LDLIBS   ?= -lcurl -lpthread

# libpq is required only for the worker target.
LIBPQ_CFLAGS :=
LIBPQ_LIBS   := -lpq

ifeq ($(OS),Windows_NT)
  LDLIBS += -lws2_32 -lbcrypt
  EXEEXT := .exe
else
  # -std=c11 hides POSIX APIs (gmtime_r, strdup) unless a feature macro is set.
  CPPFLAGS += -D_DEFAULT_SOURCE
  LDLIBS += -lcrypto
  EXEEXT :=
  # Debian/Ubuntu install libpq-fe.h under /usr/include/postgresql (via pkg-config).
  ifeq ($(shell pkg-config --exists libpq 2>/dev/null && echo yes),yes)
    LIBPQ_CFLAGS := $(shell pkg-config --cflags libpq)
    LIBPQ_LIBS   := $(shell pkg-config --libs libpq)
  else
    LIBPQ_CFLAGS := -I/usr/include/postgresql
    LIBPQ_LIBS   := -lpq
  endif
endif

BUILD_DIR := build
BIN       := $(BUILD_DIR)/netpulse-monitor$(EXEEXT)
WORKER    := $(BUILD_DIR)/netpulse-worker$(EXEEXT)
TEST_BIN  := $(BUILD_DIR)/test_monitor$(EXEEXT)
TEST_JSON := $(BUILD_DIR)/test_job_json$(EXEEXT)
TEST_SIG  := $(BUILD_DIR)/test_sigv4$(EXEEXT)

OBJ_COMMON := $(BUILD_DIR)/monitor.o $(BUILD_DIR)/url_guard.o
OBJ_WORKER := $(BUILD_DIR)/worker_main.o $(BUILD_DIR)/job_json.o \
              $(BUILD_DIR)/aws_creds.o $(BUILD_DIR)/aws_sigv4.o \
              $(BUILD_DIR)/sqs_client.o $(BUILD_DIR)/db_checks.o

WORKER_LIBS := $(LDLIBS) $(LIBPQ_LIBS)

.PHONY: all worker clean test run

all: $(BIN)

worker: $(WORKER)

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

$(BUILD_DIR)/monitor.o: src/monitor.c src/monitor.h include/url_guard.h | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c src/monitor.c -o $@

$(BUILD_DIR)/url_guard.o: src/url_guard.c include/url_guard.h | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c src/url_guard.c -o $@

$(BUILD_DIR)/main.o: src/main.c src/monitor.h | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c src/main.c -o $@

$(BUILD_DIR)/job_json.o: src/job_json.c include/job_json.h | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c src/job_json.c -o $@

$(BUILD_DIR)/aws_creds.o: src/aws_creds.c include/aws_creds.h | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c src/aws_creds.c -o $@

$(BUILD_DIR)/aws_sigv4.o: src/aws_sigv4.c include/aws_sigv4.h include/aws_creds.h | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c src/aws_sigv4.c -o $@

$(BUILD_DIR)/sqs_client.o: src/sqs_client.c include/sqs_client.h include/aws_sigv4.h | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c src/sqs_client.c -o $@

$(BUILD_DIR)/db_checks.o: src/db_checks.c include/db_checks.h src/monitor.h | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(LIBPQ_CFLAGS) $(CFLAGS) -c src/db_checks.c -o $@

$(BUILD_DIR)/worker_main.o: src/worker_main.c include/*.h src/monitor.h | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c src/worker_main.c -o $@

$(BUILD_DIR)/test_monitor.o: tests/test_monitor.c src/monitor.h include/url_guard.h | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c tests/test_monitor.c -o $@

$(BUILD_DIR)/test_job_json.o: tests/test_job_json.c include/job_json.h | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c tests/test_job_json.c -o $@

$(BIN): $(BUILD_DIR)/main.o $(OBJ_COMMON)
	$(CC) $(LDFLAGS) $^ -o $@ $(LDLIBS)

$(WORKER): $(OBJ_WORKER) $(OBJ_COMMON)
	$(CC) $(LDFLAGS) $^ -o $@ $(WORKER_LIBS)

$(TEST_BIN): $(BUILD_DIR)/test_monitor.o $(OBJ_COMMON)
	$(CC) $(LDFLAGS) $^ -o $@ $(LDLIBS)

$(TEST_JSON): $(BUILD_DIR)/test_job_json.o $(BUILD_DIR)/job_json.o
	$(CC) $(LDFLAGS) $^ -o $@

$(BUILD_DIR)/test_sigv4.o: tests/test_sigv4.c include/aws_sigv4.h | $(BUILD_DIR)
	$(CC) $(CPPFLAGS) $(CFLAGS) -c tests/test_sigv4.c -o $@

$(TEST_SIG): $(BUILD_DIR)/test_sigv4.o $(BUILD_DIR)/aws_sigv4.o
	$(CC) $(LDFLAGS) $^ -o $@ $(LDLIBS)

test: $(TEST_BIN) $(TEST_JSON) $(TEST_SIG)
	./$(TEST_BIN)
	./$(TEST_JSON)
	./$(TEST_SIG)

run: $(BIN)
	./$(BIN) https://example.com

clean:
	rm -rf $(BUILD_DIR)
