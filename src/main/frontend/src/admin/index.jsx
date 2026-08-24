import React from 'react';
import ReactDOM from 'react-dom';
import AdminApp from './AdminApp';

const root = document.getElementById('issue-notifier-admin-root');
if (root) {
  ReactDOM.render(<AdminApp />, root);
}
