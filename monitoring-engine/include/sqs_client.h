#ifndef NETPULSE_SQS_CLIENT_H
#define NETPULSE_SQS_CLIENT_H

#include "aws_creds.h"

#include <stddef.h>

typedef struct {
    char body[8192];
    char receipt_handle[2048];
    char message_id[128];
} NpSqsMessage;

typedef struct NpSqsClient NpSqsClient;

NpSqsClient *np_sqs_client_create(const char *queue_url, const char *region,
                                  const NpAwsCredentials *creds, char *err, size_t err_len);
void np_sqs_client_destroy(NpSqsClient *client);

/*
 * Long-poll receive. visibility_timeout_sec should exceed max check duration.
 * Returns 1 if a message was received, 0 on empty, -1 on error.
 */
int np_sqs_receive(NpSqsClient *client, int wait_seconds, int visibility_timeout_sec,
                   NpSqsMessage *out, char *err, size_t err_len);

int np_sqs_delete(NpSqsClient *client, const char *receipt_handle, char *err, size_t err_len);

#endif
