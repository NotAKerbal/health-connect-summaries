declare namespace Cloudflare {
  interface Env {
    DB?: D1Database;
    BUCKET?: R2Bucket;
    /** Platform service-access token for this private Site, configured as a runtime secret. */
    HC_SITE_SERVICE_TOKEN?: string;
  }
}
