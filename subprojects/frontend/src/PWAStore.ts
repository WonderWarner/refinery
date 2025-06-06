/*
 * SPDX-FileCopyrightText: 2021-2023 The Refinery Authors <https://refinery.tools/>
 *
 * SPDX-License-Identifier: EPL-2.0
 */

import { makeAutoObservable, observable } from 'mobx';
import ms from 'ms';
// eslint-disable-next-line import/no-unresolved -- Importing virtual module.
import { registerSW } from 'virtual:pwa-register';

import getLogger from './utils/getLogger';

const log = getLogger('PWAStore');

const UPDATE_INTERVAL = ms('30m');

export default class PWAStore {
  needsUpdate = false;

  updateError = false;

  private readonly updateSW: (reloadPage?: boolean) => Promise<void>;

  private registration: ServiceWorkerRegistration | undefined;

  constructor() {
    if (window.location.host === 'localhost') {
      // Do not register service worker during local development.
      this.updateSW = () => Promise.resolve();
    } else {
      this.updateSW = registerSW({
        onNeedRefresh: () => this.requestUpdate(),
        onOfflineReady() {
          log.debug('Service worker is ready for offline use');
        },
        onRegistered: (registration) => {
          log.debug('Registered service worker');
          this.setRegistration(registration);
        },
        onRegisterError(err: unknown) {
          log.error({ err }, 'Failed to register service worker');
        },
      });
      setInterval(() => this.checkForUpdates(), UPDATE_INTERVAL);
    }
    makeAutoObservable<PWAStore, 'updateSW' | 'registration'>(this, {
      updateSW: false,
      registration: observable.ref,
    });
  }

  private requestUpdate(): void {
    this.needsUpdate = true;
  }

  private setRegistration(
    registration: ServiceWorkerRegistration | undefined,
  ): void {
    this.registration = registration;
  }

  private signalError(): void {
    this.updateError = true;
  }

  private update(reloadPage?: boolean): void {
    this.updateSW(reloadPage).catch((err: unknown) => {
      log.error({ err }, 'Error while reloading page with updates');
      this.signalError();
    });
  }

  checkForUpdates(): void {
    this.dismissError();
    // In development mode, the service worker deactives itself,
    // so we must watch out for a deactivated service worker before updating.
    if (this.registration?.active) {
      this.registration.update().catch((err: unknown) => {
        log.error({ err }, 'Error while updating service worker');
        this.signalError();
      });
    }
  }

  reloadWithUpdate(): void {
    this.dismissUpdate();
    this.update(true);
  }

  dismissUpdate(): void {
    this.needsUpdate = false;
  }

  dismissError(): void {
    this.updateError = false;
  }
}
