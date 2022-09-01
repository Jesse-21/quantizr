import { AppState } from "./AppState";
import { ProgressDlg } from "./dlg/ProgressDlg";
import * as J from "./JavaIntf";
import { S } from "./Singletons";

declare const __page: any;

export class RpcUtil {
    rpcPath: string = null;
    rhost: string = null;
    logRpc: boolean = false;
    logRpcShort: boolean = false;

    /*
    * We use this variable to determine if we are waiting for an ajax call, but the server also enforces that each
    * session is only allowed one concurrent call and simultaneous calls just "queue up".
    */
    private rpcCounter: number = 0;

    timeoutMessageShown: boolean = false;
    waitCounter: number = 0;
    pgrsDlg: ProgressDlg = null;

    getRemoteHost = (): string => {
        if (this.rhost) {
            return this.rhost;
        }

        this.rhost = S.util.getParameterByName("rhost");
        this.rhost = this.rhost || window.location.origin;
        return this.rhost;
    }

    getRpcPath = (): string => {
        return this.rpcPath || (this.rpcPath = this.getRemoteHost() + "/mobile/api/");
    }

    // todo-1: put everything related to rpc in an Rpc.ts service
    rpc = <RequestType extends J.RequestBase, ResponseType>(postName: string, postData: RequestType = null,
        background: boolean = false): Promise<ResponseType> => {
        postData = postData || {} as RequestType;
        let reqPromise: Promise<ResponseType> = null;

        try {
            reqPromise = new Promise<ResponseType>((resolve, reject) => {
                if (this.logRpc) {
                    console.log("JSON-POST: [" + this.getRpcPath() + postName + "]" + S.util.prettyPrint(postData));
                }
                else if (this.logRpcShort) {
                    console.log("JSON-POST: [" + this.getRpcPath() + postName + "]");
                }

                // This setOverlay is turned back off
                if (!background) {
                    this.rpcCounter++;
                    S.quanta.setOverlay(true);
                }

                const startTime = new Date().getTime();
                // console.log("fetch: " + this.getRpcPath() + postName + " Bearer: " + S.quanta.authToken);
                fetch(this.getRpcPath() + postName, {
                    method: "POST",
                    body: JSON.stringify(postData),
                    headers: {
                        "Content-Type": "application/json",
                        Bearer: S.quanta.authToken || ""
                    },
                    mode: "cors", // no-cors, *cors, same-origin
                    cache: "no-cache", // *default, no-cache, reload, force-cache, only-if-cached
                    credentials: "same-origin", // include, *same-origin, omit
                    referrerPolicy: "no-referrer"
                })
                    .then((res: any) => {
                        if (res.status !== 200) {
                            console.log("reject: " + this.getRpcPath() + postName + " Bearer: " + S.quanta.authToken);
                            reject({ response: res });
                        }
                        else {
                            return res.text();
                        }
                    })
                    .then((json: string) => {
                        /* if we did a reject above in the first 'then' we will get here with json undefined
                        so we ignore that */
                        if (json) {
                            console.log("rpc: " + postName + " " + (new Date().getTime() - startTime) + "ms");
                            resolve(JSON.parse(json));
                        }
                    })
                    .catch((error) => {
                        console.log("reject: " + this.getRpcPath() + postName + " Bearer: " + S.quanta.authToken);
                        reject(error);
                    });
            });
        } catch (ex) {
            S.util.logAndReThrow("Failed starting request: " + postName, ex);
            if (!background) {
                S.quanta.setOverlay(false);
            }
            return null;
        }

        reqPromise.then((data: any) => this.rpcSuccess(data, background, postName))
            .catch((error: any) => this.rpcFail(error, background, postName, postData));
        return reqPromise;
    }

    rpcSuccess = (data: any, background: boolean, postName: string) => {
        try {
            if (!background) {
                this.rpcCounter--;
                this.progressInterval(null);
            }

            if (this.logRpc) {
                console.log("    JSON-RESULT: " + postName + "\n    JSON-RESULT-DATA: " +
                    S.util.prettyPrint(data));
            }

            if (!data.success && data.message) {
                // if we didn't just console log it then console log it now.
                if (!this.logRpc) {
                    console.error("FAILED JSON-RESULT: " + postName + "\n    JSON-RESULT-DATA: " +
                        S.util.prettyPrint(data));
                }
                S.util.showMessage(data.message, "Message");

                // get rid of message so it can't be shown again
                data.message = null;
                return;
            }
        } catch (ex) {
            S.util.logAndReThrow("Failed handling result of: " + postName, ex);
        }
        finally {
            if (!background) {
                S.quanta.setOverlay(false);
            }
        }
    }

    /**
     * We should only reach here when there's an actual failure to call the server, and is completely
     * separete from the server perhaps haveing an exception where it sent back an error.
     */
    rpcFail = (error: any, background: boolean, postName: string, postData: any) => {
        try {
            if (!background) {
                this.rpcCounter--;
                this.progressInterval(null);
            }
            let status = error.response ? error.response.status : "";
            const info = "Status: " + status + " message: " + error.message + " stack: " + error.stack;
            console.log("HTTP RESP [" + postName + "]: Error: " + info);

            if (error.response?.status === 401) {
                console.log("Not logged in detected.");
                if (!this.timeoutMessageShown) {
                    this.timeoutMessageShown = true;
                }
                return;
            }

            let msg: string = `Failed: \nPostName: ${postName}\n`;
            msg += "PostData: " + S.util.prettyPrint(postData) + "\n";

            if (error.response) {
                msg += "Error Response: " + S.util.prettyPrint(error.response) + "\n";
            }

            msg += info;
            console.error("Request failed: msg=" + msg);

            status = error.response ? error.response.status : "";

            if (!background) {
                console.error("Failed: " + status + " " + (error.message || ""));
                S.util.showMessage("Something went wrong. Try refreshing your browser page.", "Oops", true);
            }
        } catch (ex) {
            S.util.logAndReThrow("Failed processing: " + postName, ex);
        }
        finally {
            if (!background) {
                S.quanta.setOverlay(false);
            }
        }
    }

    isRpcWaiting = (): boolean => {
        return this.rpcCounter > 0;
    }

    initProgressMonitor = () => {
        // This timer is a singleton that runs always so we don't need to ever clear the timeout. Not a resource leak.
        setInterval(this.progressInterval, 1000);
    }

    progressInterval = (state: AppState) => {
        /* welcome.html page doesn't do the overlay (mouse blocking) or progress message when it's
         querying server like the APP would do (index.html) */
        if (__page !== "index") return;

        const isWaiting = S.rpcUtil.isRpcWaiting();
        if (isWaiting) {
            this.waitCounter++;
            if (this.waitCounter >= 3) {
                if (!this.pgrsDlg) {
                    const dlg = new ProgressDlg();
                    this.pgrsDlg = dlg;
                    this.pgrsDlg.open();
                }
            }
        } else {
            this.waitCounter = 0;
            if (this.pgrsDlg) {
                this.pgrsDlg.close();
                this.pgrsDlg = null;
            }
        }
    }

}