import { strict as assert } from 'assert';

describe('extension host reconnection guards', () => {
    function createReconnectController(maxReconnectAttempts = 10) {
        let isReconnecting = false;
        let reconnectAttempts = 0;
        let connectCalls = 0;

        async function handleDisconnect() {
            if (isReconnecting) {
                return;
            }

            if (reconnectAttempts >= maxReconnectAttempts) {
                return;
            }

            isReconnecting = true;
            reconnectAttempts++;

            await Promise.resolve();
            connect();
        }

        function connect() {
            connectCalls++;
            isReconnecting = false;
            reconnectAttempts = 0;
        }

        return {
            handleDisconnect,
            connect,
            getReconnectAttempts: () => reconnectAttempts,
            getConnectCalls: () => connectCalls,
        };
    }

    function createSessionInitializationGuard() {
        let isSessionInitialized = false;
        let initializeCalls = 0;

        function handleInitialized() {
            if (isSessionInitialized) {
                return false;
            }

            isSessionInitialized = true;
            initializeCalls++;
            return true;
        }

        return {
            handleInitialized,
            getInitializeCalls: () => initializeCalls,
        };
    }

    it('schedules only one reconnect when error and close fire back to back', async () => {
        const controller = createReconnectController();

        await Promise.all([
            controller.handleDisconnect(),
            controller.handleDisconnect(),
        ]);

        assert.equal(controller.getConnectCalls(), 1);
        assert.equal(controller.getReconnectAttempts(), 0);
    });

    it('initializes a session only once when Initialized arrives multiple times', () => {
        const guard = createSessionInitializationGuard();

        assert.equal(guard.handleInitialized(), true);
        assert.equal(guard.handleInitialized(), false);
        assert.equal(guard.handleInitialized(), false);
        assert.equal(guard.getInitializeCalls(), 1);
    });
});
