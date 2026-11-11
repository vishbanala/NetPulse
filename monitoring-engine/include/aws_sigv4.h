#ifndef NETPULSE_AWS_SIGV4_H
#define NETPULSE_AWS_SIGV4_H

#include "aws_creds.h"

#include <stddef.h>

int np_aws_sigv4_sign(
    const NpAwsCredentials *creds,
    const char *region,
    const char *service,
    const char *method,
    const char *host,
    const char *canonical_path,
    const char *payload,
    const char *amz_target,
    char *authorization,
    size_t authorization_len,
    char *amz_date,
    size_t amz_date_len,
    char *payload_hash_hex,
    size_t payload_hash_hex_len);

/* Exposed for regression tests — builds botocore-compatible SQS JSON canonical request. */
int np_aws_sigv4_build_canonical_sqs(
    const char *method,
    const char *canonical_path,
    const char *host,
    const char *amz_date,
    const char *amz_target,
    const char *session_token_or_null,
    const char *payload_hash_hex,
    char *canonical_headers_out,
    size_t canonical_headers_len,
    char *signed_headers_out,
    size_t signed_headers_len,
    char *canonical_request_out,
    size_t canonical_request_len);

int np_aws_sha256_hex(const char *data, size_t len, char *out_hex, size_t out_hex_len);
int np_aws_sigv4_self_test(char *err, size_t err_len);

#endif
