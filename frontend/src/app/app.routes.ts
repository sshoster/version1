import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  {
    path: 'welcome',
    loadComponent: () => import('./features/auth/welcome.page').then((m) => m.WelcomePage),
  },
  {
    path: 'invite/:token',
    loadComponent: () => import('./features/invite/accept-invite.page').then((m) => m.AcceptInvitePage),
  },
  {
    path: 'about',
    loadComponent: () => import('./features/info/info.page').then((m) => m.InfoPage),
    data: { pageKey: 'about' },
  },
  {
    path: 'privacy',
    loadComponent: () => import('./features/info/info.page').then((m) => m.InfoPage),
    data: { pageKey: 'privacy' },
  },
  {
    path: 'contact',
    loadComponent: () => import('./features/info/info.page').then((m) => m.InfoPage),
    data: { pageKey: 'contact' },
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./features/home/home.page').then((m) => m.HomePage),
  },
  {
    path: 'new',
    canActivate: [authGuard],
    loadComponent: () => import('./features/create/create-discussion.page').then((m) => m.CreateDiscussionPage),
  },
  {
    path: 'rooms/:roomId',
    canActivate: [authGuard],
    loadComponent: () => import('./features/room/room.page').then((m) => m.RoomPage),
  },
  {
    path: 'rooms/:roomId/result',
    canActivate: [authGuard],
    loadComponent: () => import('./features/room/result.page').then((m) => m.ResultPage),
  },
  { path: '**', redirectTo: '' },
];
