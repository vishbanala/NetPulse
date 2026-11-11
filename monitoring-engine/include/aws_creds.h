#ifndef NETPULSE_AWS_CREDS_H
#define NETPULSE_AWS_CREDS_H

#include <stddef.h>
#include <time.h>

typedef struct {
    char access_key_id[128];
    char secret_access_key[128];
    char session_token[4096]; /* empty if unused */
    time_t expiration;        /* 0 if unknown/static */
} NpAwsCredentials;

/*
 * Resolve credentials for local/dev and future ECS:
 * 1) AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_SESSION_TOKEN env
 * 2) Shared credentials file (~/.aws/credentials) for AWS_PROFILE / default
 * 3) ECS container credentials (AWS_CONTAINER_CREDENTIALS_RELATIVE_URI) when present
 *
 * Does not support aws login cache directly — export temporary env creds first.
 * Returns 0 on success. Never logs secret values.
 */
int np_aws_load_credentials(NpAwsCredentials *out, char *err, size_t err_len);

#endif
