import { setupServer } from 'msw/node';
import { handlers } from './handlers';

/** MSW server for Vitest (Node). Tests override single endpoints with `server.use(...)`. */
export const server = setupServer(...handlers);
