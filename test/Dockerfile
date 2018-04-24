FROM golang:1.10-alpine as builder
WORKDIR /go/src/github.com/salemove/deploy-pipeline-test
COPY app.go .
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o app .

FROM alpine:latest
# Make build-time argument available as an env variable
ARG BUILD_VALUE
ENV BUILD_VALUE ${BUILD_VALUE}
WORKDIR /root/
COPY --from=builder /go/src/github.com/salemove/deploy-pipeline-test/app .
CMD ["./app"]
