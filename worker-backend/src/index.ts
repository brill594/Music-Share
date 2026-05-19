import { handleRequest, runCleanup } from "./lib/app";
import type { Env } from "./lib/config";
import type { ExportedHandler } from "./lib/types";

const worker: ExportedHandler<Env> = {
  async fetch(request, env) {
    return handleRequest(request, env);
  },

  async scheduled(_controller, env, ctx) {
    ctx.waitUntil(runCleanup(env));
  },
};

export default worker;
