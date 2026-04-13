// Copyright 2009-2025 Weibo, Inc.
// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

import { fork } from 'child_process';
import * as fs from 'fs';
import * as net from 'net';
import * as os from 'os';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { VSBuffer } from '../vscode/vs/base/common/buffer.js';
import { NodeSocket } from '../vscode/vs/base/parts/ipc/node/ipc.net.js';
import { PersistentProtocol } from '../vscode/vs/base/parts/ipc/common/ipc.net.js';
import { DEBUG_PORT } from './config.js';
import { MessageType, createMessageOfType, isMessageOfType, UIKind, IExtensionHostInitData } from '../vscode/vs/workbench/services/extensions/common/extensionHostProtocol.js';
import { SocketCloseEvent, SocketCloseEventType } from '../vscode/vs/base/parts/ipc/common/ipc.net.js';
import { IDisposable } from '../vscode/vs/base/common/lifecycle.js';
import { URI } from '../vscode/vs/base/common/uri.js';
import { RPCManager } from './rpcManager.js';
import { ExtensionManager } from './extensionManager.js';

// Get current file directory path
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const LOGS_DIR = path.join(os.homedir(), '.costrict-jetbrains', 'logs');

function ensureLogsDirectory(): void {
    try {
        fs.mkdirSync(LOGS_DIR, { recursive: true });
    } catch (error) {
        console.error('Failed to ensure logs directory:', error);
    }
}

ensureLogsDirectory();

// Create ExtensionManager instance and register extension
const extensionManager = new ExtensionManager();
const rooCodeIdentifier = extensionManager.registerExtension('roo-code').identifier;

// Declare extension host process variables
let extHostProcess: ReturnType<typeof fork>;
let protocol: PersistentProtocol | null = null;
let rpcManager: RPCManager | null = null;

// Create socket server
const server = net.createServer((socket) => {
    console.log('Someone connected to main server');
    
    // Dispose old connection resources before creating new ones
    if (rpcManager) {
        rpcManager.dispose();
        rpcManager = null;
    }
    if (protocol) {
        protocol.dispose();
        protocol = null;
    }
    
    // Set socket noDelay option
    socket.setNoDelay(true);
    
    // Wrap socket with NodeSocket
    const nodeSocket = new NodeSocket(socket);
    
    // Listen for NodeSocket close events
    const closeDisposable: IDisposable = nodeSocket.onClose((event: SocketCloseEvent | undefined) => {
        console.log('NodeSocket close event received');
        if (event?.type === SocketCloseEventType.NodeSocketCloseEvent) {
            if (event.hadError) {
                console.error('Socket closed with error:', event.error);
            } else {
                console.log('Socket closed normally');
            }
        }
        closeDisposable.dispose();
    });
    
    // Create PersistentProtocol as a session-scoped local variable.
    // DO NOT reference the global `protocol` variable inside this callback —
    // the closure must only use `sessionProtocol` to avoid stale-reference bugs
    // where a delayed message from a disposed protocol fires against a new session.
    const sessionProtocol = new PersistentProtocol({
        socket: nodeSocket,
        initialChunk: null
    });
    // Also update the global reference (for SIGINT cleanup)
    protocol = sessionProtocol;

    let isSessionInitialized = false;

    // Set protocol message handler — only references sessionProtocol, never the global
    sessionProtocol.onMessage((message) => {
        if (isMessageOfType(message, MessageType.Ready)) {
            console.log('Extension host is ready');
            // Send initialization data
            const initData: IExtensionHostInitData = {
                commit: 'development',
                version: '1.0.0',
                quality: undefined,
                parentPid: process.pid,
                environment: {
                    isExtensionDevelopmentDebug: false,
                    appName: 'VSCodeAPIHook',
                    appHost: 'node',
                    appLanguage: 'en',
                    appUriScheme: 'vscode',
                    appRoot: URI.file(__dirname),
                    globalStorageHome: URI.file(path.join(__dirname, 'globalStorage')),
                    workspaceStorageHome: URI.file(path.join(__dirname, 'workspaceStorage')),
                    extensionDevelopmentLocationURI: undefined,
                    extensionTestsLocationURI: undefined,
                    useHostProxy: false,
                    skipWorkspaceStorageLock: false,
                    isExtensionTelemetryLoggingOnly: false
                },
                workspace: {
                    id: 'development-workspace',
                    name: 'Development Workspace',
                    transient: false,
                    configuration: null,
                    isUntitled: false
                },
                remote: {
                    authority: undefined,
                    connectionData: null,
                    isRemote: false
                },
                extensions: {
                    versionId: 1,
                    allExtensions: extensionManager.getAllExtensionDescriptions(),
                    myExtensions: extensionManager.getAllExtensionDescriptions().map(ext => ext.identifier),
                    activationEvents: extensionManager.getAllExtensionDescriptions().reduce((events, ext) => {
                        if (ext.activationEvents) {
                            events[ext.identifier.value] = ext.activationEvents;
                        }
                        return events;
                    }, {} as { [extensionId: string]: string[] })
                },
                telemetryInfo: {
                    sessionId: 'development-session',
                    machineId: 'development-machine',
                    sqmId: '',
                    devDeviceId: '',
                    firstSessionDate: new Date().toISOString(),
                    msftInternal: false
                },
                logLevel: 0, // Info level
                loggers: [],
                logsLocation: URI.file(LOGS_DIR),
                autoStart: true,
                consoleForward: {
                    includeStack: false,
                    logNative: false
                },
                uiKind: UIKind.Desktop
            };
            sessionProtocol.send(VSBuffer.fromString(JSON.stringify(initData)));
        } else if (isMessageOfType(message, MessageType.Initialized)) {
            if (isSessionInitialized) {
                console.warn('Extension host session already initialized, ignoring duplicate Initialized message');
                return;
            }
            isSessionInitialized = true;

            console.log('Extension host initialized');
            // Create RPCManager bound to this session's protocol
            const sessionRpcManager = new RPCManager(sessionProtocol, extensionManager);
            rpcManager = sessionRpcManager;

            sessionRpcManager.startInitialize();
            
            // Activate rooCode plugin
            const rpcProtocol = sessionRpcManager.getRPCProtocol();
            if (rpcProtocol) {
                extensionManager.activateExtension(rooCodeIdentifier.value, rpcProtocol)
                    .catch((error: Error) => {
                        console.error('Failed to load rooCode plugin:', error);
                    });
            } else {
                console.error('Failed to get RPCProtocol from RPCManager');
            }
        }
    });
});

function startExtensionHostProcess() {
    const debugPort = process.env.VSCODE_DEBUG_PORT || '0';
    let nodeOptions = process.env.VSCODE_DEBUG
        ? `--inspect-brk=${debugPort}`
        : `--inspect=${DEBUG_PORT}`
    console.log('will start extension host process with options:', nodeOptions);

    // Create extension host process and pass environment variables
    extHostProcess = fork(path.join(__dirname, 'extension.js'), [], {
        env: {
            ...process.env,
            VSCODE_EXTHOST_WILL_SEND_SOCKET: '1',
            VSCODE_EXTHOST_SOCKET_HOST: '127.0.0.1',
            VSCODE_EXTHOST_SOCKET_PORT: (server.address() as net.AddressInfo)?.port?.toString() || '0',
            NODE_OPTIONS: nodeOptions
        }
    });

    // Handle extension host process exit
    extHostProcess.on('exit', (code: number | null, signal: string | null) => {
        console.log(`Extension host process exited with code ${code} and signal ${signal}`);
        server.close();
    });
}

// Listen on random port
server.listen(0, '127.0.0.1', () => {
    const address = server.address();
    if (address && typeof address !== 'string') {
        console.log(`Server listening on port ${address.port}`);
        startExtensionHostProcess();
    }
});

// Handle process exit
process.on('SIGINT', () => {
    console.log('Cleaning up...');
    if (protocol) {
        protocol.send(createMessageOfType(MessageType.Terminate));
    }
    server.close();
    if (extHostProcess) {
        extHostProcess.kill();
    }
    process.exit(0);
});

