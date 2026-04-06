import { createContext, useCallback, useContext, useState, type ReactNode } from 'react'

interface DrawerState {
  selectedJobId: string | null
  selectedGroupId: string | null
  openJob: (jobId: string) => void
  openSkillGroup: (groupId: string) => void
  closeJob: () => void
  closeSkillGroup: () => void
}

const DrawerContext = createContext<DrawerState | null>(null)

export function DrawerProvider({ children }: { children: ReactNode }) {
  const [selectedJobId, setSelectedJobId] = useState<string | null>(null)
  const [selectedGroupId, setSelectedGroupId] = useState<string | null>(null)

  const openJob = useCallback((jobId: string) => {
    setSelectedGroupId(null)
    setSelectedJobId(jobId)
  }, [])

  const openSkillGroup = useCallback((groupId: string) => {
    setSelectedJobId(null)
    setSelectedGroupId(groupId)
  }, [])

  const closeJob = useCallback(() => {
    setSelectedJobId(null)
  }, [])

  const closeSkillGroup = useCallback(() => {
    setSelectedGroupId(null)
  }, [])

  return (
    <DrawerContext.Provider value={{
      selectedJobId,
      selectedGroupId,
      openJob,
      openSkillGroup,
      closeJob,
      closeSkillGroup,
    }}>
      {children}
    </DrawerContext.Provider>
  )
}

export function useDrawer(): DrawerState {
  const context = useContext(DrawerContext)
  if (!context) {
    throw new Error('useDrawer must be used within a DrawerProvider')
  }
  return context
}
