export const getToken = () => localStorage.getItem('claw_token');
export const setToken = (t) => localStorage.setItem('claw_token', t);
export const clearToken = () => localStorage.removeItem('claw_token');
export const isAuthed = () => !!getToken();
