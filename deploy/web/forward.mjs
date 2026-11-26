// Loopback forwarder that owns the network namespace of the web panel ("webnet" service; the web container joins it
// with network_mode: service:webnet). The panel only accepts loopback HTTP origins, and the identity provider's
// issuer must be the same URL for the browser (on this PC) and for the panel's server, so inside this namespace:
//   127.0.0.1:9011 -> fusionauth:9011   (issuer http://127.0.0.1:9011, also published on the PC's loopback)
//   127.0.0.1:8080 -> backend:8080      (API_BASE_URL of the panel's server)
import net from "node:net";

const routes = [
  { port: 9011, host: "fusionauth", target: 9011 },
  { port: 8080, host: "backend", target: 8080 },
];

for (const { port, host, target } of routes) {
  net
    .createServer((client) => {
      const upstream = net.connect(target, host);
      client.pipe(upstream).pipe(client);
      const close = () => {
        client.destroy();
        upstream.destroy();
      };
      client.on("error", close);
      upstream.on("error", close);
    })
    .listen(port, "127.0.0.1", () => console.log(`127.0.0.1:${port} -> ${host}:${target}`));
}
