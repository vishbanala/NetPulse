#include "aws_sigv4.h"

#include <stdio.h>
#include <string.h>
#include <time.h>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <bcrypt.h>
#else
#include <openssl/evp.h>
#include <openssl/hmac.h>
#endif

static void to_hex(const unsigned char *in, size_t len, char *out, size_t out_len)
{
    static const char *hex = "0123456789abcdef";
    size_t i;
    if (out_len < len * 2 + 1) {
        if (out_len > 0) {
            out[0] = '\0';
        }
        return;
    }
    for (i = 0; i < len; i++) {
        out[i * 2] = hex[(in[i] >> 4) & 0xF];
        out[i * 2 + 1] = hex[in[i] & 0xF];
    }
    out[len * 2] = '\0';
}

#ifdef _WIN32
static int sha256_raw(const unsigned char *data, size_t len, unsigned char out[32])
{
    BCRYPT_ALG_HANDLE alg = NULL;
    BCRYPT_HASH_HANDLE hash = NULL;
    int ok = -1;

    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, NULL, 0) != 0) {
        goto done;
    }
    if (BCryptCreateHash(alg, &hash, NULL, 0, NULL, 0, 0) != 0) {
        goto done;
    }
    if (len > 0 && BCryptHashData(hash, (PUCHAR)data, (ULONG)len, 0) != 0) {
        goto done;
    }
    if (BCryptFinishHash(hash, out, 32, 0) != 0) {
        goto done;
    }
    ok = 0;
done:
    if (hash) {
        BCryptDestroyHash(hash);
    }
    if (alg) {
        BCryptCloseAlgorithmProvider(alg, 0);
    }
    return ok;
}

static int hmac_sha256(const unsigned char *key, size_t key_len,
                       const unsigned char *data, size_t data_len,
                       unsigned char out[32])
{
    BCRYPT_ALG_HANDLE alg = NULL;
    BCRYPT_HASH_HANDLE hash = NULL;
    DWORD hash_obj_len = 0;
    DWORD data_len_dw = 0;
    PBYTE hash_obj = NULL;
    int ok = -1;

    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, NULL,
                                   BCRYPT_ALG_HANDLE_HMAC_FLAG) != 0) {
        goto done;
    }
    if (BCryptGetProperty(alg, BCRYPT_OBJECT_LENGTH, (PBYTE)&hash_obj_len,
                          sizeof(DWORD), &data_len_dw, 0) != 0) {
        goto done;
    }
    hash_obj = (PBYTE)HeapAlloc(GetProcessHeap(), 0, hash_obj_len);
    if (!hash_obj) {
        goto done;
    }
    if (BCryptCreateHash(alg, &hash, hash_obj, hash_obj_len, (PBYTE)key, (ULONG)key_len, 0) != 0) {
        goto done;
    }
    if (data_len > 0 && BCryptHashData(hash, (PUCHAR)data, (ULONG)data_len, 0) != 0) {
        goto done;
    }
    if (BCryptFinishHash(hash, out, 32, 0) != 0) {
        goto done;
    }
    ok = 0;
done:
    if (hash) {
        BCryptDestroyHash(hash);
    }
    if (hash_obj) {
        HeapFree(GetProcessHeap(), 0, hash_obj);
    }
    if (alg) {
        BCryptCloseAlgorithmProvider(alg, 0);
    }
    return ok;
}
#else
static int sha256_raw(const unsigned char *data, size_t len, unsigned char out[32])
{
    unsigned int out_len = 32;
    return EVP_Digest(data, len, out, &out_len, EVP_sha256(), NULL) == 1 ? 0 : -1;
}

static int hmac_sha256(const unsigned char *key, size_t key_len,
                       const unsigned char *data, size_t data_len,
                       unsigned char out[32])
{
    unsigned int out_len = 32;
    return HMAC(EVP_sha256(), key, (int)key_len, data, data_len, out, &out_len) != NULL ? 0 : -1;
}
#endif

int np_aws_sha256_hex(const char *data, size_t len, char *out_hex, size_t out_hex_len)
{
    unsigned char dig[32];
    if (sha256_raw((const unsigned char *)(data ? data : ""), len, dig) != 0) {
        return -1;
    }
    to_hex(dig, 32, out_hex, out_hex_len);
    return 0;
}

static int derive_signing_key(const char *secret, const char *date_stamp,
                              const char *region, const char *service,
                              unsigned char out[32])
{
    unsigned char k_date[32];
    unsigned char k_region[32];
    unsigned char k_service[32];
    char k_secret[256];
    int n;

    n = snprintf(k_secret, sizeof(k_secret), "AWS4%s", secret);
    if (n <= 0 || (size_t)n >= sizeof(k_secret)) {
        return -1;
    }

    if (hmac_sha256((unsigned char *)k_secret, (size_t)n,
                    (unsigned char *)date_stamp, strlen(date_stamp), k_date) != 0) {
        return -1;
    }
    if (hmac_sha256(k_date, 32, (unsigned char *)region, strlen(region), k_region) != 0) {
        return -1;
    }
    if (hmac_sha256(k_region, 32, (unsigned char *)service, strlen(service), k_service) != 0) {
        return -1;
    }
    return hmac_sha256(k_service, 32, (unsigned char *)"aws4_request", 12, out);
}

/*
 * Build the SQS JSON-protocol canonical request exactly as botocore does:
 * signed headers = content-type;host;x-amz-date;[x-amz-security-token;]x-amz-target;x-amzn-query-mode
 * NOTE: SQS JSON does NOT sign/send x-amz-content-sha256 (unlike S3).
 * The hashed payload is still the final line of the canonical request.
 */
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
    size_t canonical_request_len)
{
    int has_token = (session_token_or_null && session_token_or_null[0]);
    const char *content_type = "application/x-amz-json-1.0";
    const char *query_mode = "true";

    if (has_token) {
        snprintf(signed_headers_out, signed_headers_len,
                 "content-type;host;x-amz-date;x-amz-security-token;x-amz-target;x-amzn-query-mode");
        snprintf(canonical_headers_out, canonical_headers_len,
                 "content-type:%s\n"
                 "host:%s\n"
                 "x-amz-date:%s\n"
                 "x-amz-security-token:%s\n"
                 "x-amz-target:%s\n"
                 "x-amzn-query-mode:%s\n",
                 content_type, host, amz_date, session_token_or_null, amz_target, query_mode);
    } else {
        snprintf(signed_headers_out, signed_headers_len,
                 "content-type;host;x-amz-date;x-amz-target;x-amzn-query-mode");
        snprintf(canonical_headers_out, canonical_headers_len,
                 "content-type:%s\n"
                 "host:%s\n"
                 "x-amz-date:%s\n"
                 "x-amz-target:%s\n"
                 "x-amzn-query-mode:%s\n",
                 content_type, host, amz_date, amz_target, query_mode);
    }

    /* CanonicalHeaders already ends with \n; one more \n makes the required blank line. */
    snprintf(canonical_request_out, canonical_request_len,
             "%s\n"
             "%s\n"
             "\n"
             "%s"
             "\n"
             "%s\n"
             "%s",
             method,
             canonical_path,
             canonical_headers_out,
             signed_headers_out,
             payload_hash_hex);
    return 0;
}

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
    size_t payload_hash_hex_len)
{
    time_t now = time(NULL);
    struct tm gmt;
    char date_stamp[16];
    unsigned char payload_hash[32];
    unsigned char signing_key[32];
    unsigned char signature[32];
    char signature_hex[65];
    char canonical_headers[8192];
    char signed_headers[192];
    char canonical_request[12288];
    char credential_scope[256];
    char string_to_sign[4608];
    unsigned char sts_hash[32];
    char sts_hash_hex[65];
    size_t payload_len = payload ? strlen(payload) : 0;
    int has_token;

    if (!creds || !region || !service || !method || !host || !canonical_path ||
        !authorization || !amz_date || !payload_hash_hex) {
        return -1;
    }
    if (amz_target == NULL) {
        amz_target = "";
    }
    has_token = creds->session_token[0] != '\0';

#ifdef _WIN32
    gmtime_s(&gmt, &now);
#else
    gmtime_r(&now, &gmt);
#endif
    strftime(amz_date, amz_date_len, "%Y%m%dT%H%M%SZ", &gmt);
    strftime(date_stamp, sizeof(date_stamp), "%Y%m%d", &gmt);

    if (sha256_raw((const unsigned char *)(payload ? payload : ""), payload_len, payload_hash) != 0) {
        return -1;
    }
    to_hex(payload_hash, 32, payload_hash_hex, payload_hash_hex_len);

    if (np_aws_sigv4_build_canonical_sqs(
            method, canonical_path, host, amz_date, amz_target,
            has_token ? creds->session_token : NULL, payload_hash_hex,
            canonical_headers, sizeof(canonical_headers),
            signed_headers, sizeof(signed_headers),
            canonical_request, sizeof(canonical_request)) != 0) {
        return -1;
    }

    if (sha256_raw((unsigned char *)canonical_request, strlen(canonical_request), sts_hash) != 0) {
        return -1;
    }
    to_hex(sts_hash, 32, sts_hash_hex, sizeof(sts_hash_hex));

    snprintf(credential_scope, sizeof(credential_scope), "%s/%s/%s/aws4_request",
             date_stamp, region, service);
    snprintf(string_to_sign, sizeof(string_to_sign),
             "AWS4-HMAC-SHA256\n%s\n%s\n%s",
             amz_date, credential_scope, sts_hash_hex);

    if (derive_signing_key(creds->secret_access_key, date_stamp, region, service, signing_key) != 0) {
        return -1;
    }
    if (hmac_sha256(signing_key, 32, (unsigned char *)string_to_sign, strlen(string_to_sign),
                    signature) != 0) {
        return -1;
    }
    to_hex(signature, 32, signature_hex, sizeof(signature_hex));

    snprintf(authorization, authorization_len,
             "AWS4-HMAC-SHA256 Credential=%s/%s, SignedHeaders=%s, Signature=%s",
             creds->access_key_id, credential_scope, signed_headers, signature_hex);

    fprintf(stderr,
            "SigV4: service=%s region=%s host=%s target=%s payload_len=%zu "
            "has_session_token=%d signed_headers=%s\n",
            service, region, host, amz_target, payload_len, has_token, signed_headers);

    return 0;
}

int np_aws_sigv4_self_test(char *err, size_t err_len)
{
    char hex[65];
    unsigned char hmac_out[32];
    char hmac_hex[65];
    char canon_hdrs[2048];
    char signed_hdrs[192];
    char canon_req[4096];

    if (np_aws_sha256_hex("", 0, hex, sizeof(hex)) != 0 ||
        strcmp(hex, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") != 0) {
        snprintf(err, err_len, "SHA256 empty-string vector failed");
        return -1;
    }

    if (hmac_sha256((unsigned char *)"key", 3,
                    (unsigned char *)"The quick brown fox jumps over the lazy dog", 43,
                    hmac_out) != 0) {
        snprintf(err, err_len, "HMAC compute failed");
        return -1;
    }
    to_hex(hmac_out, 32, hmac_hex, sizeof(hmac_hex));
    if (strcmp(hmac_hex, "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8") != 0) {
        snprintf(err, err_len, "HMAC-SHA256 vector failed");
        return -1;
    }

    {
        unsigned char k_date[32];
        char k_date_hex[65];
        char k_secret[128];
        snprintf(k_secret, sizeof(k_secret), "AWS4%s",
                 "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");
        if (hmac_sha256((unsigned char *)k_secret, strlen(k_secret),
                        (unsigned char *)"20150830", 8, k_date) != 0) {
            snprintf(err, err_len, "kDate derive failed");
            return -1;
        }
        to_hex(k_date, 32, k_date_hex, sizeof(k_date_hex));
        if (strncmp(k_date_hex, "0138c7a6cbd60aa7", 16) != 0) {
            snprintf(err, err_len, "AWS kDate vector failed");
            return -1;
        }
    }

    /*
     * Deterministic regression: full SQS JSON canonical request must match
     * botocore (signed headers include x-amzn-query-mode; no x-amz-content-sha256).
     */
    {
        static const char *payload_hash =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        static const char *expected_canon =
            "POST\n"
            "/\n"
            "\n"
            "content-type:application/x-amz-json-1.0\n"
            "host:sqs.us-east-2.amazonaws.com\n"
            "x-amz-date:20261110T203640Z\n"
            "x-amz-security-token:SESSION_TOKEN_PLACEHOLDER\n"
            "x-amz-target:AmazonSQS.GetQueueAttributes\n"
            "x-amzn-query-mode:true\n"
            "\n"
            "content-type;host;x-amz-date;x-amz-security-token;x-amz-target;x-amzn-query-mode\n"
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        /* Precomputed: SHA256(expected_canon) then SigV4 with AWS example secret. */
        static const char *expected_canon_hash =
            "5e38b5f9c9ca8d9b4021f44757c71c37324068edc7149e36080d5bdf9864d02f";
        static const char *expected_signature =
            "4485061a35d9837b0b0f11b4ad7cc8167c7274798ef96a4966bac4f1b36e6020";
        unsigned char canon_digest[32];
        char canon_digest_hex[65];
        unsigned char signing_key[32];
        unsigned char sig[32];
        char sig_hex[65];
        char string_to_sign[512];
        char credential_scope[128];

        if (np_aws_sigv4_build_canonical_sqs(
                "POST", "/", "sqs.us-east-2.amazonaws.com", "20261110T203640Z",
                "AmazonSQS.GetQueueAttributes", "SESSION_TOKEN_PLACEHOLDER",
                payload_hash, canon_hdrs, sizeof(canon_hdrs), signed_hdrs,
                sizeof(signed_hdrs), canon_req, sizeof(canon_req)) != 0) {
            snprintf(err, err_len, "canonical build failed");
            return -1;
        }
        if (strcmp(signed_hdrs,
                   "content-type;host;x-amz-date;x-amz-security-token;"
                   "x-amz-target;x-amzn-query-mode") != 0) {
            snprintf(err, err_len, "signed headers mismatch: %s", signed_hdrs);
            return -1;
        }
        if (strstr(canon_hdrs, "x-amz-content-sha256:") != NULL) {
            snprintf(err, err_len, "SQS canonical headers must not include x-amz-content-sha256");
            return -1;
        }
        if (strcmp(canon_req, expected_canon) != 0) {
            snprintf(err, err_len, "full canonical request mismatch");
            return -1;
        }

        if (sha256_raw((unsigned char *)canon_req, strlen(canon_req), canon_digest) != 0) {
            snprintf(err, err_len, "canonical SHA256 failed");
            return -1;
        }
        to_hex(canon_digest, 32, canon_digest_hex, sizeof(canon_digest_hex));

        snprintf(credential_scope, sizeof(credential_scope),
                 "20261110/us-east-2/sqs/aws4_request");
        snprintf(string_to_sign, sizeof(string_to_sign),
                 "AWS4-HMAC-SHA256\n20261110T203640Z\n%s\n%s",
                 credential_scope, canon_digest_hex);

        if (derive_signing_key("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                               "20261110", "us-east-2", "sqs", signing_key) != 0) {
            snprintf(err, err_len, "signing key derive failed");
            return -1;
        }
        if (hmac_sha256(signing_key, 32, (unsigned char *)string_to_sign,
                        strlen(string_to_sign), sig) != 0) {
            snprintf(err, err_len, "signature HMAC failed");
            return -1;
        }
        to_hex(sig, 32, sig_hex, sizeof(sig_hex));

        if (strcmp(canon_digest_hex, expected_canon_hash) != 0) {
            snprintf(err, err_len, "canonical request hash mismatch");
            return -1;
        }
        if (strcmp(sig_hex, expected_signature) != 0) {
            snprintf(err, err_len, "SigV4 signature mismatch");
            return -1;
        }
    }

    return 0;
}
