export class ApiError extends Error {
    constructor(code, status) {
        super(code);
        this.code = code;
        this.status = status;
    }
}

/** @returns {never} */
export function fail(code, status) {
    throw new ApiError(code, status);
}
