/** Token pair returned by login and refresh (ADR-022 D10: both in the body, never a cookie). */
export interface TokenPair {
  access_token: string;
  refresh_token: string;
}

export interface LoginCredentials {
  username: string;
  password: string;
}
