package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"flag"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
)

func main() {
	listen := flag.String("listen", "0.0.0.0:56000", "DTLS listen address (ip:port)")
	httpListen := flag.String("http-listen", "", "Optional plain TCP listen address for HTTP proxy (e.g. 0.0.0.0:8080)")
	timeout := flag.Duration("timeout", 30*time.Minute, "Connection idle timeout")
	flag.Parse()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	signalChan := make(chan os.Signal, 1)
	signal.Notify(signalChan, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-signalChan
		log.Println("Shutting down...")
		cancel()
		<-signalChan
		log.Fatal("Forced exit")
	}()

	wg := &sync.WaitGroup{}

	wg.Add(1)
	go func() {
		defer wg.Done()
		runDTLSListener(ctx, *listen, *timeout)
	}()

	if *httpListen != "" {
		wg.Add(1)
		go func() {
			defer wg.Done()
			runTCPListener(ctx, *httpListen, *timeout)
		}()
	}

	log.Printf("Server started. DTLS on %s", *listen)
	if *httpListen != "" {
		log.Printf("Plain TCP HTTP proxy on %s", *httpListen)
	}

	wg.Wait()
	log.Println("Server stopped")
}

func runDTLSListener(ctx context.Context, addr string, idleTimeout time.Duration) {
	udpAddr, err := net.ResolveUDPAddr("udp", addr)
	if err != nil {
		log.Fatalf("Failed to resolve UDP address: %v", err)
	}

	certificate, err := selfsign.GenerateSelfSigned()
	if err != nil {
		log.Fatalf("Failed to generate certificate: %v", err)
	}

	config := &dtls.Config{
		Certificates:          []tls.Certificate{certificate},
		ExtendedMasterSecret:  dtls.RequireExtendedMasterSecret,
		CipherSuites:          []dtls.CipherSuiteID{dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256},
		ConnectionIDGenerator: dtls.RandomCIDGenerator(8),
	}

	listener, err := dtls.Listen("udp", udpAddr, config)
	if err != nil {
		log.Fatalf("Failed to start DTLS listener: %v", err)
	}

	context.AfterFunc(ctx, func() {
		listener.Close()
	})

	log.Printf("DTLS listener on %s", addr)

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		conn, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("DTLS accept error: %v", err)
			continue
		}

		go handleDTLSConnection(ctx, conn, idleTimeout)
	}
}

func handleDTLSConnection(ctx context.Context, conn net.Conn, idleTimeout time.Duration) {
	defer conn.Close()
	log.Printf("DTLS connection from %s", conn.RemoteAddr())

	dtlsConn, ok := conn.(*dtls.Conn)
	if !ok {
		log.Println("Type assertion to *dtls.Conn failed")
		return
	}

	handshakeCtx, handshakeCancel := context.WithTimeout(ctx, 30*time.Second)
	defer handshakeCancel()

	if err := dtlsConn.HandshakeContext(handshakeCtx); err != nil {
		log.Printf("DTLS handshake failed: %v", err)
		return
	}
	log.Printf("DTLS handshake complete with %s", conn.RemoteAddr())

	handleProxyConnection(ctx, conn, idleTimeout)
}

func runTCPListener(ctx context.Context, addr string, idleTimeout time.Duration) {
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatalf("Failed to start TCP listener: %v", err)
	}

	context.AfterFunc(ctx, func() {
		listener.Close()
	})

	log.Printf("TCP listener on %s", addr)

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		conn, err := listener.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("TCP accept error: %v", err)
			continue
		}

		go handleProxyConnection(ctx, conn, idleTimeout)
	}
}

// handleProxyConnection reads HTTP requests from the connection and proxies them.
// Supports both regular HTTP requests and CONNECT tunneling (for HTTPS).
func handleProxyConnection(ctx context.Context, conn net.Conn, idleTimeout time.Duration) {
	defer conn.Close()
	reader := bufio.NewReader(conn)

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		conn.SetReadDeadline(time.Now().Add(idleTimeout))

		req, err := http.ReadRequest(reader)
		if err != nil {
			if err != io.EOF && !isClosedError(err) {
				log.Printf("Read request error from %s: %v", conn.RemoteAddr(), err)
			}
			return
		}

		log.Printf("[%s] %s %s", conn.RemoteAddr(), req.Method, req.RequestURI)

		if req.Method == http.MethodConnect {
			handleConnectMethod(ctx, conn, req, idleTimeout)
			return // CONNECT takes over the connection
		}

		handleHTTPRequest(conn, req)
	}
}

func handleConnectMethod(ctx context.Context, clientConn net.Conn, req *http.Request, idleTimeout time.Duration) {
	host := req.Host
	if !strings.Contains(host, ":") {
		host = host + ":443"
	}

	targetConn, err := net.DialTimeout("tcp", host, 10*time.Second)
	if err != nil {
		log.Printf("CONNECT dial error to %s: %v", host, err)
		clientConn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\n\r\n"))
		return
	}
	defer targetConn.Close()

	clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	connCtx, connCancel := context.WithCancel(ctx)
	defer connCancel()

	context.AfterFunc(connCtx, func() {
		clientConn.SetDeadline(time.Now())
		targetConn.SetDeadline(time.Now())
	})

	wg := sync.WaitGroup{}
	wg.Add(2)

	go func() {
		defer wg.Done()
		defer connCancel()
		relay(clientConn, targetConn, idleTimeout)
	}()

	go func() {
		defer wg.Done()
		defer connCancel()
		relay(targetConn, clientConn, idleTimeout)
	}()

	wg.Wait()
}

func handleHTTPRequest(clientConn net.Conn, req *http.Request) {
	if !req.URL.IsAbs() {
		req.URL.Scheme = "http"
		req.URL.Host = req.Host
	}

	removeHopByHopHeaders(req.Header)

	transport := &http.Transport{
		DialContext: (&net.Dialer{
			Timeout:   10 * time.Second,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		MaxIdleConns:          100,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
	}
	defer transport.CloseIdleConnections()

	req.RequestURI = ""
	resp, err := transport.RoundTrip(req)
	if err != nil {
		log.Printf("Proxy request error: %v", err)
		clientConn.Write([]byte("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n"))
		return
	}
	defer resp.Body.Close()

	if writeErr := resp.Write(clientConn); writeErr != nil {
		log.Printf("Write response error: %v", writeErr)
	}
}

func relay(dst net.Conn, src net.Conn, idleTimeout time.Duration) {
	buf := make([]byte, 32*1024)
	for {
		src.SetReadDeadline(time.Now().Add(idleTimeout))
		n, err := src.Read(buf)
		if n > 0 {
			dst.SetWriteDeadline(time.Now().Add(30 * time.Second))
			if _, wErr := dst.Write(buf[:n]); wErr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}

func removeHopByHopHeaders(h http.Header) {
	hopByHop := []string{
		"Connection", "Keep-Alive", "Proxy-Authenticate",
		"Proxy-Authorization", "TE", "Trailers",
		"Transfer-Encoding", "Upgrade",
	}
	for _, header := range hopByHop {
		h.Del(header)
	}
}

func isClosedError(err error) bool {
	if err == nil {
		return false
	}
	s := err.Error()
	return strings.Contains(s, "use of closed network connection") ||
		strings.Contains(s, "connection reset by peer") ||
		strings.Contains(s, "broken pipe") ||
		strings.Contains(s, "i/o timeout")
}
