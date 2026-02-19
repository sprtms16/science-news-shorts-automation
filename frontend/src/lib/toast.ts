import { toast } from 'sonner';

export const showSuccess = (message: string) => toast.success(message);
export const showError = (message: string) => toast.error(message);
export const showInfo = (message: string) => toast.info(message);

export function confirmAction(message: string): Promise<boolean> {
    return new Promise((resolve) => {
        toast(message, {
            duration: Infinity,
            action: {
                label: 'Confirm',
                onClick: () => resolve(true),
            },
            cancel: {
                label: 'Cancel',
                onClick: () => resolve(false),
            },
            onDismiss: () => resolve(false),
        });
    });
}
