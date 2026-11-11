#include "aws_sigv4.h"

#include <stdio.h>
#include <string.h>

int main(void)
{
    char err[256];
    char hex[65];

    if (np_aws_sha256_hex("", 0, hex, sizeof(hex)) != 0) {
        fprintf(stderr, "sha256 failed\n");
        return 1;
    }
    printf("PASS  sha256 empty = %.16s...\n", hex);

    if (np_aws_sigv4_self_test(err, sizeof(err)) != 0) {
        fprintf(stderr, "FAIL  %s\n", err);
        return 1;
    }
    printf("PASS  SigV4 self-test (SHA256, HMAC, kDate, full SQS canonical + signature)\n");
    return 0;
}
