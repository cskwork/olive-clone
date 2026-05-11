#!/usr/bin/env bash
# LocalStack ready.d hook: create the dev bucket once the s3 service is up.
# Spring boot side has a fallback createBucket() if this script ever skips,
# so this is "best effort" — never fail the container if the call errors.
set -u

awslocal s3 mb "s3://${BUCKET:-commerce-images-local}" >/dev/null 2>&1 || true
